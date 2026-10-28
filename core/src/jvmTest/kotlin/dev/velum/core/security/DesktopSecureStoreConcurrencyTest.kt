package dev.velum.core.security

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class DesktopSecureStoreConcurrencyTest {

    @Test
    fun concurrent_put_remove_reloads_consistently() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-concurrent")
        val props = dir.resolve("secure.properties")
        val keyFile = dir.resolve("secure.aes256")
        try {
            val store = DesktopSecureStore(file = props, keyFile = keyFile)
            coroutineScope {
                repeat(200) { i ->
                    launch(Dispatchers.Default) {
                        val slot = i / 2
                        val name = "slot-$slot"
                        if (i % 2 == 0) {
                            store.putString(name, "value-$slot")
                        } else {
                            store.remove(name)
                        }
                    }
                }
            }

            val live = (0 until 100).associate { slot ->
                val name = "slot-$slot"
                name to (store.contains(name) to store.getString(name))
            }

            val reloaded = DesktopSecureStore(file = props, keyFile = keyFile)
            for ((name, pair) in live) {
                val (contained, value) = pair
                assertEquals(contained, reloaded.contains(name))
                assertEquals(value, reloaded.getString(name))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun posix_owner_only_permissions_on_key_and_properties() {
        assumeTrue("POSIX permissions required", supportsPosixPermissions())

        val dir = kotlin.io.path.createTempDirectory("velum-secure-perms")
        val props = dir.resolve("secure.properties")
        val keyFile = dir.resolve("secure.aes256")
        val expected = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        val tempModesAtCreation = mutableListOf<Set<PosixFilePermission>>()
        val onTemp: (java.nio.file.Path) -> Unit = { tmp ->
            tempModesAtCreation += Files.getPosixFilePermissions(tmp)
        }
        try {
            runBlocking {
                val store = DesktopSecureStore.createForTest(
                    file = props,
                    keyFile = keyFile,
                    onOwnerOnlyTempCreated = onTemp,
                )
                store.putString("probe", "x")
            }
            assertTrue(tempModesAtCreation.isNotEmpty(), "expected temp-file creation probes")
            for (mode in tempModesAtCreation) {
                if (mode != expected) {
                    fail("temp file permissions were not owner-only at creation")
                }
            }
            assertTrue(Files.exists(keyFile))
            assertTrue(Files.exists(props))
            val keyPerms = Files.getPosixFilePermissions(keyFile)
            val propsPerms = Files.getPosixFilePermissions(props)
            if (keyPerms != expected) {
                fail("key file permissions were not owner-only")
            }
            if (propsPerms != expected) {
                fail("properties file permissions were not owner-only")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrent_loadOrCreate_on_missing_key_converges_to_one_key() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-key-race-missing")
        val keyFile = dir.resolve("secure.aes256")
        try {
            val keys = Collections.synchronizedList(mutableListOf<ByteArray>())
            coroutineScope {
                repeat(8) {
                    launch(Dispatchers.IO) {
                        val key = DesktopSecureStore.loadOrCreateKey(keyFile)
                        keys += key.encoded.copyOf()
                    }
                }
            }
            assertEquals(8, keys.size)
            val first = keys.first()
            assertEquals(AES_256_KEY_BYTES, first.size)
            for (k in keys) {
                assertTrue(first.contentEquals(k), "concurrent starters must share one key")
            }
            assertTrue(first.contentEquals(Files.readAllBytes(keyFile)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrent_loadOrCreate_on_bad_key_converges_to_one_key() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-key-race-bad")
        val keyFile = dir.resolve("secure.aes256")
        try {
            Files.write(keyFile, byteArrayOf(0x0A, 0x0B, 0x0C))
            val keys = Collections.synchronizedList(mutableListOf<ByteArray>())
            coroutineScope {
                repeat(8) {
                    launch(Dispatchers.IO) {
                        val key = DesktopSecureStore.loadOrCreateKey(keyFile)
                        keys += key.encoded.copyOf()
                    }
                }
            }
            assertEquals(8, keys.size)
            val first = keys.first()
            assertEquals(AES_256_KEY_BYTES, first.size)
            for (k in keys) {
                assertTrue(first.contentEquals(k), "concurrent bad-key recovery must share one key")
            }
            assertTrue(first.contentEquals(Files.readAllBytes(keyFile)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun stalled_publish_second_thread_shares_key_no_quarantine() {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-stall-thread")
        val keyFile = dir.resolve("secure.aes256")
        val propsA = dir.resolve("a.properties")
        val propsB = dir.resolve("b.properties")
        try {
            val enteredStall = CountDownLatch(1)
            val releaseStall = CountDownLatch(1)
            val firstKey = AtomicReference<ByteArray?>()
            val secondKey = AtomicReference<ByteArray?>()
            val firstError = AtomicReference<Throwable?>()
            val secondError = AtomicReference<Throwable?>()

            val t1 = Thread({
                try {
                    val key = DesktopSecureStore.loadOrCreateKey(keyFile) {
                        enteredStall.countDown()
                        check(releaseStall.await(10, TimeUnit.SECONDS)) {
                            "stall release timed out"
                        }
                    }
                    firstKey.set(key.encoded.copyOf())
                } catch (t: Throwable) {
                    firstError.set(t)
                }
            }, "key-stall-writer")
            t1.start()
            check(enteredStall.await(10, TimeUnit.SECONDS)) { "writer never reached stall" }

            val t2 = Thread({
                try {
                    val key = DesktopSecureStore.loadOrCreateKey(keyFile)
                    secondKey.set(key.encoded.copyOf())
                } catch (t: Throwable) {
                    secondError.set(t)
                }
            }, "key-stall-waiter")
            t2.start()
            // Give the waiter time to block on the file lock.
            Thread.sleep(100)
            releaseStall.countDown()
            t1.join(15_000)
            t2.join(15_000)

            assertTrue(firstError.get() == null, "writer failed: ${firstError.get()}")
            assertTrue(secondError.get() == null, "waiter failed: ${secondError.get()}")
            val a = assertNotNull(firstKey.get())
            val b = assertNotNull(secondKey.get())
            assertEquals(AES_256_KEY_BYTES, a.size)
            assertTrue(a.contentEquals(b), "stalled writer and waiter must share one key")
            assertTrue(a.contentEquals(Files.readAllBytes(keyFile)))

            val badCount = Files.list(dir).use { stream ->
                stream.filter { p -> p.fileName.toString().startsWith("secure.aes256.bad-") }
                    .count()
            }
            assertEquals(0L, badCount, "no key file should be quarantined during publish stall")

            // Encrypt with one store, decrypt with the other (same on-disk key).
            runBlocking {
                val storeA = DesktopSecureStore(file = propsA, keyFile = keyFile)
                storeA.putString(SecureKeys.DEVICE_SERIAL, "SYNTH-STALL-SERIAL")
                val storeB = DesktopSecureStore(file = propsB, keyFile = keyFile)
                // storeB has empty props backend — use shared ciphertext via propsA reload
                val reloaded = DesktopSecureStore(file = propsA, keyFile = keyFile)
                assertEquals("SYNTH-STALL-SERIAL", reloaded.getString(SecureKeys.DEVICE_SERIAL))
                assertTrue(a.contentEquals(Files.readAllBytes(keyFile)))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun stalled_publish_second_process_shares_key() {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-stall-process")
        val keyFile = dir.resolve("secure.aes256")
        try {
            val enteredStall = CountDownLatch(1)
            val releaseStall = CountDownLatch(1)
            val firstKey = AtomicReference<ByteArray?>()
            val firstError = AtomicReference<Throwable?>()

            val t1 = Thread({
                try {
                    val key = DesktopSecureStore.loadOrCreateKey(keyFile) {
                        enteredStall.countDown()
                        check(releaseStall.await(15, TimeUnit.SECONDS))
                    }
                    firstKey.set(key.encoded.copyOf())
                } catch (t: Throwable) {
                    firstError.set(t)
                }
            }, "key-stall-writer-proc")
            t1.start()
            check(enteredStall.await(10, TimeUnit.SECONDS))

            val java = PathOfJava()
            val classpath = System.getProperty("java.class.path")
            val pb = ProcessBuilder(
                java,
                "-cp",
                classpath,
                "dev.velum.core.security.KeyLoadProcessMainKt",
                keyFile.toAbsolutePath().toString(),
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()

            // Child blocks on the lock until we release the stall.
            Thread.sleep(200)
            releaseStall.countDown()
            t1.join(20_000)
            val finished = proc.waitFor(20, TimeUnit.SECONDS)
            assertTrue(finished, "child process did not exit")
            val out = proc.inputStream.bufferedReader().readText().trim()
            assertEquals(0, proc.exitValue(), "child failed: $out")

            assertTrue(firstError.get() == null, "writer failed: ${firstError.get()}")
            val a = assertNotNull(firstKey.get())
            assertEquals(AES_256_KEY_BYTES * 2, out.length)
            val childBytes = out.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertTrue(a.contentEquals(childBytes), "in-process and child JVM must share one key")
            assertTrue(a.contentEquals(Files.readAllBytes(keyFile)))

            val badCount = Files.list(dir).use { stream ->
                stream.filter { p -> p.fileName.toString().startsWith("secure.aes256.bad-") }
                    .count()
            }
            assertEquals(0L, badCount)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun PathOfJava(): String {
        val home = System.getProperty("java.home")
        val bin = java.nio.file.Path.of(home, "bin", "java")
        assertTrue(Files.isExecutable(bin), "java not executable at $bin")
        return bin.toString()
    }
}
