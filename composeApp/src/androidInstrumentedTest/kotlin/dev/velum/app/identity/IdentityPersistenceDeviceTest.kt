package dev.velum.app.identity

import android.app.Application
import android.content.Context
import android.os.Process
import android.system.Os
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.core.security.AndroidSecureStore
import dev.velum.core.security.PlaudExportImporter
import dev.velum.core.security.PlaudExportParseResult
import dev.velum.core.security.SecureDeviceKeyImporter
import dev.velum.core.security.SecureKeys
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.Arrays
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Three explicit host-controlled phases, in different processes. Uses only new
 * UUID-namespaced synthetic vaults. Never reads or resets the production vault.
 */
@RunWith(AndroidJUnit4::class)
class IdentityPersistenceDeviceTest {
    @Test fun synthetic_identity_persists_encrypted_across_a_new_process() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        val arguments = InstrumentationRegistry.getArguments()
        val run = arguments.getString("identityFixtureRun").orEmpty()
        require(run.length == 32 && run.all { it in 'a'..'f' || it in '0'..'9' }) { "A fresh synthetic fixture UUID is required" }
        val phase = arguments.getString("identityFixturePhase").orEmpty()
        require(phase == "prepare" || phase == "verify" || phase == "cleanup") { "Explicit fixture phase required" }
        val directory = File(context.filesDir, "synthetic-identity-verification")
        val metadata = File(directory, "$run.json")
        val pid = Process.myPid()
        val start = Process.getStartElapsedRealtime()
        Log.i(TAG, "begin run=$run phase=$phase pid=$pid")
        when (phase) {
            "prepare" -> {
                assertTrue("Fixture UUID was already used", !metadata.exists())
                assertTrue("Fixture directory unavailable", directory.isDirectory || directory.mkdirs())
                // Reserve both namespaces before creating either vault. A pre-existing
                // alias/file collision must never become eligible for diagnostic cleanup.
                for (kind in KINDS) {
                    val fixture = Fixture(context, run, kind)
                    assertTrue("Synthetic alias collision", !fixture.keyStore().containsAlias(fixture.alias))
                    assertTrue("Synthetic preference file collision", !fixture.preferenceFile().exists())
                    assertTrue("Synthetic preferences collision", fixture.preferences().all.isEmpty())
                }
                val info = JSONObject().put("run", run).put("first_pid", pid).put("first_start_elapsed", start)
                    .put("phase", "preparing").put("namespace_reserved", true)
                writeMetadata(metadata, info)
                for (kind in KINDS) {
                    val fixture = Fixture(context, run, kind)
                    if (kind == "json") {
                        val result = PlaudExportImporter.importInto(fixture.json(), fixture.importer(), null)
                        assertTrue("Synthetic JSON import failed", result is PlaudExportParseResult.Success)
                    } else {
                        fixture.importer().importKeys(fixture.serial, fixture.signature, fixture.token, fixture.publicPem, fixture.privatePem)
                    }
                    fixture.checkLoaded()
                    fixture.checkEncryptedPrivatePreferences()
                    fixture.checkMalformedPreservesCiphertext()
                }
                writeMetadata(metadata, info.put("phase", "prepared"))
            }
            "verify" -> {
                val info = JSONObject(readText(metadata))
                assertEquals(run, info.getString("run"))
                assertEquals("prepared", info.getString("phase"))
                assertTrue("Fixture namespace was not reserved", info.getBoolean("namespace_reserved"))
                assertTrue("No new process was observed", info.getInt("first_pid") != pid)
                assertTrue("Process start did not change", info.getLong("first_start_elapsed") != start)
                for (kind in KINDS) {
                    val fixture = Fixture(context, run, kind)
                    fixture.checkLoaded()
                    fixture.checkEncryptedPrivatePreferences()
                    fixture.checkMalformedPreservesCiphertext()
                }
                writeMetadata(metadata, info.put("phase", "verified").put("verified_pid", pid).put("verified_start_elapsed", start))
            }
            "cleanup" -> {
                val info = JSONObject(readText(metadata))
                assertEquals(run, info.getString("run"))
                assertTrue("Fixture namespace was not reserved", info.getBoolean("namespace_reserved"))
                val priorPhase = info.getString("phase")
                val verified = priorPhase == "verified"
                if (!verified) {
                    // Diagnostic cleanup is an explicit separate action, never an acceptance pass.
                    assertEquals("true", arguments.getString("identityFixtureAllowFailedCleanup"))
                    assertTrue("Unknown fixture state", priorPhase == "preparing" || priorPhase == "prepared")
                }
                for (kind in KINDS) {
                    val fixture = Fixture(context, run, kind)
                    // Explicit cleanup of only the two aliases/preferences created by this UUID.
                    fixture.keyStore().deleteEntry(fixture.alias)
                    context.deleteSharedPreferences(fixture.prefsName)
                    assertTrue("Synthetic alias cleanup failed", !fixture.keyStore().containsAlias(fixture.alias))
                    assertTrue("Synthetic preference cleanup failed", !fixture.preferenceFile().exists())
                }
                writeMetadata(metadata, info.put("cleanup_from_phase", priorPhase).put("phase", if (verified) "cleaned" else "failed-fixture-cleaned"))
            }
        }
        Log.i(TAG, "end run=$run phase=$phase pid=$pid")
    }

    private class Fixture(val context: Context, run: String, kind: String) {
        val prefsName = "velum_synthetic_identity_${run}_$kind"
        val alias = "dev.velum.synthetic.identity.$run.$kind"
        val serial = "SYNTHETIC-$kind-SERIAL"
        val token = "SYNTHETIC-$kind-BIND-TOKEN"
        val publicPem = "SYNTHETIC-$kind-PUBLIC-PEM"
        val privatePem = "SYNTHETIC-$kind-PRIVATE-PEM"
        val signatureBytes = ByteArray(256) { ((it * 7) % 251).toByte() }
        val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun preferences() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        fun preferenceFile() = File(context.applicationInfo.dataDir, "shared_prefs/$prefsName.xml")
        fun importer() = SecureDeviceKeyImporter(AndroidSecureStore(context, prefsName, alias))
        fun json(signatureValue: String = signature) = JSONObject().put("version", 1).put("source", "synthetic-test").put("device", JSONObject()
            .put("serial", serial).put("snSignatureBase64", signatureValue).put("bindToken", token)
            .put("publicKeyPem", publicPem).put("privateKeyPem", privatePem)).toString()

        suspend fun checkLoaded() {
            val loaded = importer().loadKeys()
            assertTrue("Synthetic identity missing", loaded != null)
            requireNotNull(loaded)
            // Generic assertion messages avoid printing identity even if a regression fails.
            assertTrue("Synthetic serial mismatch", loaded.serialNumber == serial)
            assertTrue("Synthetic signature mismatch", Arrays.equals(loaded.serialSignature, signatureBytes))
            assertTrue("Synthetic token mismatch", loaded.bindToken == token)
            assertTrue("Synthetic public key mismatch", loaded.publicKeyPem == publicPem)
            assertTrue("Synthetic private key mismatch", loaded.privateKeyPem == privatePem)
        }

        fun checkEncryptedPrivatePreferences() {
            assertTrue("Synthetic Keystore entry missing", keyStore().getEntry(alias, null) is KeyStore.SecretKeyEntry)
            val file = preferenceFile()
            val stat = Os.stat(file.absolutePath)
            // Android14 ContextImpl.setFilePermissionsFromMode deliberately uses 0660
            // for MODE_PRIVATE. The group must be this app's own GID, never a shared group.
            assertTrue("Preferences have other-user permissions", stat.st_mode and 0b111 == 0)
            assertEquals(Process.myUid(), stat.st_uid)
            assertEquals(Process.myUid(), stat.st_gid)
            assertEquals(Os.getgid(), stat.st_gid)
            val directory = Os.stat(context.applicationInfo.dataDir)
            assertEquals(Process.myUid(), directory.st_uid)
            assertEquals(Os.getgid(), directory.st_gid)
            assertTrue("App directory is accessible to other users", directory.st_mode and 0b111 == 0)
            val raw = readText(file)
            val plaintext = listOf(serial, signature, token, publicPem, privatePem)
            for (value in plaintext) assertTrue("Plaintext identity in private preferences", !raw.contains(value))
            val prefs = preferences()
            for (key in listOf(SecureKeys.DEVICE_SERIAL, SecureKeys.DEVICE_SERIAL_SIGNATURE, SecureKeys.DEVICE_BIND_TOKEN, SecureKeys.DEVICE_PUBLIC_KEY_PEM, SecureKeys.DEVICE_PRIVATE_KEY_PEM)) {
                val payload = Base64.decode(requireNotNull(prefs.getString(key, null)), Base64.DEFAULT)
                assertTrue("Missing encrypted payload", payload.size > 29)
                val ivLength = payload[0].toInt() and 255
                assertTrue("Invalid encrypted payload", ivLength in 12..32 && payload.size > ivLength + 17)
            }
        }

        suspend fun checkMalformedPreservesCiphertext() {
            val before = java.util.HashMap(preferences().all)
            val malformed = json("#SYNTHETIC-INVALID-SIGNATURE")
            assertTrue("Malformed JSON reported success", PlaudExportImporter.importInto(malformed, importer(), null) is PlaudExportParseResult.Failure)
            try {
                importer().importKeys(serial, "#SYNTHETIC-INVALID-SIGNATURE", token, publicPem, privatePem)
                throw AssertionError("Malformed pasted signature reported success")
            } catch (_: IllegalArgumentException) { /* expected controlled validation error */ }
            assertTrue("Malformed input changed ciphertext", before == preferences().all)
        }
    }

    private companion object {
        const val TAG = "SyntheticIdentityFixture"
        val KINDS = arrayOf("json", "paste")
        fun readText(file: File): String = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        fun writeMetadata(file: File, info: JSONObject) {
            Files.write(file.toPath(), info.toString().toByteArray(Charsets.UTF_8))
        }
    }
}
