package dev.velum.core.security

import dev.velum.protocol.identity.IdentityBundleFormat
import dev.velum.protocol.identity.IdentityRedaction
import dev.velum.protocol.identity.SyntheticIdentityFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PlaudExportImporterTest {
    @Test
    fun parse_sample_fixture_succeeds() {
        val raw = readResource("plaud_export_sample.json")
        val result = PlaudExportImporter.parse(raw)
        val ok = assertIs<PlaudExportParseResult.Success>(result)
        assertEquals("881DEMO01", ok.device.serial)
        assertEquals("demo-user-token", ok.device.bindToken)
        assertEquals(1, ok.templates.size)
        assertTrue(ok.templates[0].localId.startsWith(PLAUD_EXPORT_TEMPLATE_ID_PREFIX))
    }

    @Test
    fun parse_synthetic_usb_route_bundle() {
        val raw = SyntheticIdentityFixtures.validBundleJson(
            templatesJson = """[{"name":"Demo custom","category":"meeting","systemPrompt":"Summarize"}]""",
        )
        val ok = assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.parse(raw))
        assertEquals(SyntheticIdentityFixtures.SERIAL, ok.device.serial)
        assertEquals(SyntheticIdentityFixtures.BIND_TOKEN, ok.device.bindToken)
        assertEquals(1, ok.templates.size)
    }

    @Test
    fun rejects_wrong_signature_length() {
        val raw = """
            {
              "version": 1,
              "source": "plaud_app_export",
              "device": {
                "serial": "x",
                "snSignatureBase64": "YQ==",
                "bindToken": "t"
              }
            }
        """.trimIndent()
        val result = PlaudExportImporter.parse(raw)
        val fail = assertIs<PlaudExportParseResult.Failure>(result)
        assertTrue(fail.message.contains("256"))
        assertFalse(fail.message.contains("YQ=="))
    }

    @Test
    fun importInto_stores_keys() = runBlocking {
        val store = InMemorySecureStore()
        val importer = SecureDeviceKeyImporter(store)
        val raw = readResource("plaud_export_sample.json")
        val result = PlaudExportImporter.importInto(raw, importer)
        assertIs<PlaudExportParseResult.Success>(result)
        assertTrue(importer.hasKeys())
        assertEquals("881DEMO01", importer.loadKeys()?.serialNumber)
    }

    @Test
    fun importInto_malformed_does_not_store_keys() = runBlocking {
        val store = InMemorySecureStore()
        val importer = SecureDeviceKeyImporter(store)
        val fail = assertIs<PlaudExportParseResult.Failure>(
            PlaudExportImporter.importInto("{not-json", importer),
        )
        assertTrue(fail.message.contains("malformed", ignoreCase = true), fail.message)
        assertFalse(importer.hasKeys())
        assertFalse(store.contains(SecureKeys.DEVICE_SERIAL))
    }

    @Test
    fun importInto_overwrite_from_synthetic_fixture() = runBlocking {
        val store = InMemorySecureStore()
        val importer = SecureDeviceKeyImporter(store)
        val first = SyntheticIdentityFixtures.validBundleJson()
        assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.importInto(first, importer))
        val second = SyntheticIdentityFixtures.validBundleJson(source = SyntheticIdentityFixtures.SOURCE_EXPORT)
        assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.importInto(second, importer))
        assertEquals(SyntheticIdentityFixtures.SERIAL, importer.loadKeys()?.serialNumber)
        assertTrue(importer.hasKeys())
    }

    @Test
    fun rejects_missing_serial() {
        val sig = SyntheticIdentityFixtures.signatureBase64()
        val raw = """
            {
              "version": 1,
              "source": "plaud_app_export",
              "device": {
                "serial": "  ",
                "snSignatureBase64": "$sig",
                "bindToken": "t"
              }
            }
        """.trimIndent()
        val fail = assertIs<PlaudExportParseResult.Failure>(PlaudExportImporter.parse(raw))
        assertTrue(fail.message.contains("serial"), fail.message)
        assertFalse(fail.message.contains(sig))
    }

    @Test
    fun rejects_missing_bind_token() {
        val sig = SyntheticIdentityFixtures.signatureBase64()
        val raw = """
            {
              "version": 1,
              "source": "plaud_app_export",
              "device": {
                "serial": "x",
                "snSignatureBase64": "$sig",
                "bindToken": ""
              }
            }
        """.trimIndent()
        val fail = assertIs<PlaudExportParseResult.Failure>(PlaudExportImporter.parse(raw))
        assertTrue(fail.message.contains("bindToken"), fail.message)
        assertFalse(fail.message.contains(sig))
    }

    @Test
    fun rejects_wrong_version_without_leaking_secrets() {
        val bundle = SyntheticIdentityFixtures.validBundle(includePems = true)
        val raw = SyntheticIdentityFixtures.validBundleJson(includePems = true)
            .replace(
                "\"version\": ${IdentityBundleFormat.VERSION}",
                "\"version\": 7",
            )
        val fail = assertIs<PlaudExportParseResult.Failure>(PlaudExportImporter.parse(raw))
        assertTrue(fail.message.contains("7"))
        assertFalse(IdentityRedaction.containsSecret(fail.message, bundle))
        assertFalse(IdentityRedaction.containsSecret(fail.toString(), bundle))
    }

    @Test
    fun success_toString_redacts_identity() {
        val raw = SyntheticIdentityFixtures.validBundleJson(includePems = true)
        val ok = assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.parse(raw))
        assertFalse(IdentityRedaction.containsSecret(ok.toString(), ok.let {
            SyntheticIdentityFixtures.validBundle(includePems = true)
        }))
        assertFalse(IdentityRedaction.containsSecret(ok.device.toString(), SyntheticIdentityFixtures.validBundle(includePems = true)))
    }

    private fun readResource(name: String): String {
        val stream = PlaudExportImporterTest::class.java.classLoader!!.getResourceAsStream(name)
            ?: error("Missing test resource $name")
        return stream.bufferedReader().readText()
    }
}
