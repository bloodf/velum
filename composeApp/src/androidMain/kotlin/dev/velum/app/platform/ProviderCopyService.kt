package dev.velum.app.platform

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val PROVIDER_COPY_DEADLINE_MS = 300_000L
internal const val COPY_BEGIN = IBinder.FIRST_CALL_TRANSACTION
internal const val COPY_STOP = COPY_BEGIN + 1
internal const val COPY_DONE = COPY_BEGIN + 2
internal const val COPY_HELLO = COPY_BEGIN + 3
internal const val COPY_PROTOCOL = "dev.velum.app.providerCopy"

internal fun sendCopyMessage(binder: IBinder, code: Int, oneWay: Boolean = true, write: (Parcel) -> Unit) {
    val parcel = Parcel.obtain()
    try {
        parcel.writeInterfaceToken(COPY_PROTOCOL)
        write(parcel)
        if (!binder.transact(code, parcel, null, if (oneWay) IBinder.FLAG_ONEWAY else 0)) throw IOException("Copy IPC rejected")
    } finally {
        parcel.recycle()
    }
}

/** Provider calls run only on the worker thread. Binder control and the watchdog never call a provider. */
class ProviderCopyService : Service() {
    @Volatile private var session: String? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    private fun terminate() {
        // No external PID is ever accepted, looked up, or killed.
        if (session != null && isProviderCopyProcess(this)) Process.killProcess(Process.myPid())
    }

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in COPY_BEGIN..COPY_HELLO) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(COPY_PROTOCOL)
            if (Binder.getCallingUid() != Process.myUid() || Binder.getCallingPid() == Process.myPid()) return false
            if (!isProviderCopyProcess(this@ProviderCopyService)) return false
            val token = data.readString() ?: return false
            synchronized(this@ProviderCopyService) {
                when (code) {
                    COPY_HELLO -> {
                        if (session != null) return false
                        val callback = data.readStrongBinder() ?: return false
                        session = token
                        callback.linkToDeath({ terminate() }, 0)
                        // Startup without BEGIN must also be reclaimable.
                        val startupDeadline = ProviderCopyDeadline(SystemClock.elapsedRealtime() + PROVIDER_COPY_DEADLINE_MS, SystemClock::elapsedRealtime, ::terminate)
                        watchdog.scheduleWithFixedDelay(startupDeadline::check, 0, 100, TimeUnit.MILLISECONDS)
                        sendCopyMessage(callback, COPY_HELLO) { it.writeString(token); it.writeString(packageName + PROVIDER_COPY_PROCESS_SUFFIX); it.writeInt(Process.myPid()) }
                    }
                    COPY_STOP -> {
                        if (session != token) return false
                        terminate()
                    }
                    COPY_BEGIN -> {
                        if (session != token) return false
                        val uri = android.net.Uri.parse(data.readString() ?: return false)
                        val deadline = data.readLong()
                        val output = data.readParcelable<ParcelFileDescriptor>(ParcelFileDescriptor::class.java.classLoader) ?: return false
                        val callback = data.readStrongBinder() ?: run { output.close(); return false }
                        // Consume the session once, but retain its token for authenticated STOP.
                        if (started) { output.close(); return false }
                        started = true
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0 || remaining > PROVIDER_COPY_DEADLINE_MS) { output.close(); terminate(); return true }
                        val copyDeadline = ProviderCopyDeadline(deadline, SystemClock::elapsedRealtime, ::terminate)
                        watchdog.scheduleWithFixedDelay(copyDeadline::check, 0, 100, TimeUnit.MILLISECONDS)
                        Thread({ copy(uri, output, callback, token, deadline) }, "provider-copy").start()
                    }
                    else -> return false
                }
            }
            return true
        }
    }
    private var started = false

    private fun copy(uri: android.net.Uri, output: ParcelFileDescriptor, callback: IBinder, token: String, deadline: Long) {
        var name: String? = null
        val success = runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { sink ->
                var knownLength: Long? = null
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (n >= 0 && !cursor.isNull(n)) name = IncomingShare.cleanName(cursor.getString(n))
                        val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (size >= 0 && !cursor.isNull(size)) knownLength = cursor.getLong(size)
                    }
                }
                if (name == null) name = IncomingShare.cleanName(uri.lastPathSegment)
                if (knownLength != null && knownLength!! > MAX_PROVIDER_COPY_BYTES) throw IOException("Import exceeds size limit")
                contentResolver.openInputStream(uri)?.use { source ->
                    copyProviderBytes(source, sink, checkCancelled = {
                        if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Import deadline exceeded")
                    })
                } ?: throw IOException("Provider unavailable")
                sink.flush()
                output.fileDescriptor.sync()
            }
        }.isSuccess
        runCatching {
            sendCopyMessage(callback, COPY_DONE, oneWay = false) { it.writeString(token); it.writeInt(if (success) 1 else 0); it.writeString(name) }
        }
        // Parent waits for Binder death before publication, so no provider close can outlive success.
        terminate()
    }

    override fun onBind(intent: Intent?): IBinder = endpoint
    override fun onDestroy() {
        if (session != null) terminate()
        watchdog.shutdownNow()
        super.onDestroy()
    }
}
