package dev.velum.core.security

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
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
        try {
            val expected = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
            // Key file mode right after creation, before any properties data is written.
            createOwnerOnlyFile(keyFile)
            assertTrue(Files.exists(keyFile))
            val keyPermsAtCreation = Files.getPosixFilePermissions(keyFile)
            if (keyPermsAtCreation != expected) {
                fail("key file permissions were not owner-only at creation")
            }
            Files.delete(keyFile)

            runBlocking {
                val store = DesktopSecureStore(file = props, keyFile = keyFile)
                store.putString("probe", "x")
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
}
