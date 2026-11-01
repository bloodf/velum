package dev.velum.core.security

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class IdentityImportLogRedactionTest {
    @Test
    fun successful_and_failed_imports_do_not_write_identity_to_stdout_or_stderr() {
        val marker = "SYNTHETIC-IDENTITY-LOG-SECRET"
        val signature = Base64.getEncoder().encodeToString(ByteArray(256) { 11 })
        val raw = """{"version":1,"source":"synthetic","device":{"serial":"$marker","snSignatureBase64":"$signature","bindToken":"$marker","publicKeyPem":"$marker","privateKeyPem":"$marker"}}"""
        val stdout = System.out
        val stderr = System.err
        val captured = ByteArrayOutputStream()
        PrintStream(captured, true, Charsets.UTF_8).use { stream ->
            try {
                System.setOut(stream)
                System.setErr(stream)
                runBlocking {
                    val importer = SecureDeviceKeyImporter(InMemorySecureStore())
                    assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.importInto(raw, importer))
                    assertIs<PlaudExportParseResult.Failure>(PlaudExportImporter.importInto(raw.replace("\"$signature\"", "{}"), importer))
                    assertIs<PlaudExportParseResult.Failure>(PlaudExportImporter.importInto(raw.replace(signature, "#$marker"), importer))
                }
            } finally {
                System.setOut(stdout)
                System.setErr(stderr)
            }
        }
        val log = captured.toString(Charsets.UTF_8)
        assertFalse(log.contains(marker))
        assertFalse(log.contains(signature))
    }
}
