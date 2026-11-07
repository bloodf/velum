package dev.velum.app.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ProviderCopyResult(val file: File, val displayName: String?)

/** A single live child at a time; the next request cannot reuse a child with another session. */
private val providerCopyLock = Mutex()
private val providerCopyTimer = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "provider-copy-deadline").apply { isDaemon = true }
}

/** No provider calls occur here. Time includes bind, metadata, open, read and close. */
@androidx.annotation.VisibleForTesting
internal var providerCopyTestTransport: (suspend (Context, Uri, Boolean) -> ProviderCopyResult?)? = null

internal suspend fun copyProviderInPrivateProcess(context: Context, uri: Uri, share: Boolean): ProviderCopyResult? {
    providerCopyTestTransport?.let { return it(context, uri, share) }
    return copyProviderViaBinder(context, uri, share)
}

private suspend fun copyProviderViaBinder(context: Context, uri: Uri, share: Boolean): ProviderCopyResult? = providerCopyLock.withLock {
    val deadline = SystemClock.elapsedRealtime() + PROVIDER_COPY_DEADLINE_MS
    val token = UUID.randomUUID().toString()
    val files = ProviderCopyFiles(File(context.cacheDir, "imports"), token, share)
    val partial = files.partial
    val done = CompletableDeferred<Boolean>()
    val guard = ProviderCopySession(token, context.packageName, Process.myUid(), Process.myPid(), deadline)
    var remote: IBinder? = null
    var bound = false
    val monitor = Any()
    fun stop() {
        remote?.let { binder -> runCatching { sendCopyMessage(binder, COPY_STOP) { it.writeString(token) } } }
    }
    val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != COPY_HELLO && code != COPY_DONE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(COPY_PROTOCOL)
            synchronized(monitor) {
                val request = data.readString()
                if (code == COPY_HELLO) {
                    val childProcess = data.readString()
                    val childPid = data.readInt()
                    // Oneway Binder has no calling PID. UID + private Binder/session capability
                    // authenticate; the PID is diagnostic and is never a termination target.
                    if (!guard.hello(request, Binder.getCallingUid(), childPid, childProcess, SystemClock.elapsedRealtime())) { stop(); return false }
                    if (done.isCompleted) { stop(); return false }
                    if (!partial.exists()) { guard.abort(); stop(); done.complete(false); return false }
                    val fd = ParcelFileDescriptor.open(partial, ParcelFileDescriptor.MODE_WRITE_ONLY)
                    try {
                        sendCopyMessage(checkNotNull(remote), COPY_BEGIN) {
                            it.writeString(token)
                            it.writeString(uri.toString())
                            it.writeLong(deadline)
                            it.writeParcelable(fd, 0)
                            it.writeStrongBinder(this)
                        }
                    } finally { fd.close() }
                } else {
                    val success = data.readInt() == 1
                    val name = data.readString()
                    guard.completed(request, Binder.getCallingUid(), Binder.getCallingPid(), success, name, SystemClock.elapsedRealtime())
                }
            }
            return true
        }
    }
    val death = IBinder.DeathRecipient {
        synchronized(monitor) { done.complete(guard.died(SystemClock.elapsedRealtime())) }
    }
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(monitor) {
                remote = service
                try {
                    service.linkToDeath(death, 0)
                    sendCopyMessage(service, COPY_HELLO) { it.writeString(token); it.writeStrongBinder(callback) }
                    if (done.isCompleted) stop()
                } catch (_: Exception) { guard.abort(); done.complete(false) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { death.binderDied() }
        override fun onBindingDied(name: ComponentName) { death.binderDied() }
        override fun onNullBinding(name: ComponentName) { synchronized(monitor) { guard.abort(); done.complete(false) } }
    }
    val deadlineWatchdog = ProviderCopyDeadline(deadline, SystemClock::elapsedRealtime) {
        synchronized(monitor) {
            guard.abort()
            stop()
            done.complete(false)
        }
    }
    val timer = providerCopyTimer.scheduleWithFixedDelay(deadlineWatchdog::check, 0, 100, TimeUnit.MILLISECONDS)
    try {
        // Identity is parent-owned before binding or any fallible provider work. Child receives only an FD.
        val intent = Intent(context, ProviderCopyService::class.java)
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound || !done.await()) return@withLock null
        currentCoroutineContext().ensureActive()
        val result = files.publish(guard.displayName)
        currentCoroutineContext().ensureActive()
        files.accept()
        result
    } finally {
        // Cancellation reaches this immediately; provider threads live in the reclaimable child only.
        withContext(NonCancellable) {
            synchronized(monitor) { guard.abort(); stop() }
            timer.cancel(false)
            if (bound) runCatching { context.unbindService(connection) }
            remote?.let { runCatching { it.unlinkToDeath(death, 0) } }
            files.close()
        }
    }
}

/** Request ownership and stale-event rejection, independent of Android IPC for deterministic tests. */
internal class ProviderCopySession(
    private val token: String,
    private val packageName: String,
    private val uid: Int,
    private val mainPid: Int,
    private val deadline: Long,
) {
    private var childPid: Int? = null
    private var aborted = false
    private var complete = false
    var displayName: String? = null
        private set

    fun hello(request: String?, callerUid: Int, pid: Int, process: String?, now: Long): Boolean {
        if (aborted || childPid != null || now >= deadline || request != token || callerUid != uid || pid <= 0 || pid == mainPid || process != packageName + PROVIDER_COPY_PROCESS_SUFFIX) return false
        childPid = pid
        return true
    }

    fun completed(request: String?, callerUid: Int, pid: Int, success: Boolean, name: String?, now: Long) {
        if (aborted || complete || now >= deadline || request != token || callerUid != uid || childPid != pid) return
        complete = success
        displayName = name
    }

    fun abort() { aborted = true }
    fun died(now: Long): Boolean {
        val success = !aborted && complete && childPid != null && now < deadline
        aborted = true
        return success
    }
}

/** The parent alone owns names/publication; the child only receives the open partial descriptor. */
internal class ProviderCopyFiles(private val imports: File, token: String, private val share: Boolean) : AutoCloseable {
    private val sessionDir = File(imports, ".copy_$token")
    val partial = File(sessionDir, "partial")
    private val id = token
    private var published: File? = null
    private var accepted = false

    init {
        imports.mkdirs()
        check(sessionDir.mkdir()) { "Cannot reserve copy directory" }
        try {
            Files.createFile(partial.toPath())
        } catch (failure: Exception) {
            sessionDir.delete()
            throw failure
        }
    }

    fun publish(name: String?): ProviderCopyResult {
        val extension = name?.takeIf { ImportAudioFormats.isSupportedFileName(it) }?.substringAfterLast('.')?.lowercase() ?: "audio"
        val target = File(imports, "${if (share) "share_" else ""}$id.$extension")
        Files.move(partial.toPath(), target.toPath())
        published = target
        return ProviderCopyResult(target, name)
    }

    fun accept() { accepted = true }
    override fun close() {
        partial.delete()
        sessionDir.delete()
        if (!accepted) published?.delete()
    }
}
