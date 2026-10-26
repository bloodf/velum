package dev.velum.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun rejects_missing_serial() {
        val sig = readResource("plaud_export_sample.json").let {
            Regex("\"snSignatureBase64\":\\s*\"([^\"]+)\"").find(it)!!.groupValues[1]
        }
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
    }

    @Test
    fun rejects_missing_bind_token() {
        val sig = readResource("plaud_export_sample.json").let {
            Regex("\"snSignatureBase64\":\\s*\"([^\"]+)\"").find(it)!!.groupValues[1]
        }
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
    }

    private fun readResource(name: String): String {
        val stream = PlaudExportImporterTest::class.java.classLoader!!.getResourceAsStream(name)
            ?: error("Missing test resource $name")
        return stream.bufferedReader().readText()
    }
}
