package dev.velum.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking

class PlaudExportImporterTest {
    @Test
    fun malformed_json_diagnostics_never_include_identity_or_decoder_details() {
        val raw = """{"version":1,"source":"SYNTHETIC-SOURCE-SECRET","device":{"serial":"SYNTHETIC-SERIAL-SECRET","snSignatureBase64":{},"bindToken":"SYNTHETIC-TOKEN-SECRET","privateKeyPem":"SYNTHETIC-PEM-SECRET"}}"""
        val failure = assertIs<PlaudExportParseResult.Failure>(PlaudExportImporter.parse(raw))
        assertEquals("Invalid JSON", failure.message)
        assertFalse(failure.toString().contains("SYNTHETIC-"))
    }

    @Test
    fun malformed_signature_diagnostics_are_fixed_without_input_or_decoder_cause() {
        val raw = readResource("plaud_export_sample.json").replace(
            Regex("\"snSignatureBase64\":\\s*\"[^\"]+\""),
            "\"snSignatureBase64\":\"#SYNTHETIC-SIGNATURE-SECRET\"",
        )
        val failure = assertIs<PlaudExportParseResult.Failure>(PlaudExportImporter.parse(raw))
        assertEquals("device.snSignatureBase64 is not valid base64", failure.message)
    }

    @Test
    fun partial_or_invalid_bundles_never_write_any_entry() = runBlocking {
        val sample = readResource("plaud_export_sample.json")
        val inputs = listOf("", "{}", sample.replace("\"version\": 1", "\"version\": 2")) +
            listOf("serial", "snSignatureBase64", "bindToken").map { field ->
                sample.replace(Regex("\"$field\":\\s*\"[^\"]+\""), "\"$field\":\"\"")
            }
        for (raw in inputs) {
            val store = InMemorySecureStore()
            store.putString(SecureKeys.DEVICE_BIND_TOKEN, "SYNTHETIC-PRESERVED")
            assertIs<PlaudExportParseResult.Failure>(PlaudExportImporter.importInto(raw, SecureDeviceKeyImporter(store)))
            assertEquals("SYNTHETIC-PRESERVED", store.getString(SecureKeys.DEVICE_BIND_TOKEN))
            assertFalse(store.contains(SecureKeys.DEVICE_SERIAL))
            assertFalse(store.contains(SecureKeys.DEVICE_SERIAL_SIGNATURE))
        }
    }

    @Test
    fun identity_objects_are_redacted_in_diagnostics_without_changing_serialization() = runBlocking {
        val raw = readResource("plaud_export_sample.json")
        val wire = Json.decodeFromString(PlaudExportFile.serializer(), raw)
        val success = assertIs<PlaudExportParseResult.Success>(PlaudExportImporter.parse(raw))
        val importer = SecureDeviceKeyImporter(InMemorySecureStore())
        PlaudExportImporter.importInto(raw, importer)
        for (value in listOf(wire, wire.device, success, success.device, importer.loadKeys())) {
            assertTrue(value.toString().contains("[redacted]"))
            assertFalse(value.toString().contains(wire.device.serial))
            assertFalse(value.toString().contains(wire.device.bindToken))
            assertFalse(value.toString().contains(wire.device.snSignatureBase64))
        }
        assertEquals(wire, Json.decodeFromString(PlaudExportFile.serializer(), Json.encodeToString(PlaudExportFile.serializer(), wire)))
    }

    @Test
    fun cancellation_from_import_still_propagates_without_false_success() = runBlocking {
        val cancellation = CancellationException("Synthetic cancellation")
        val store = object : SecureStore {
            override suspend fun putString(key: String, value: String): Unit = throw cancellation
            override suspend fun getString(key: String): String? = null
            override suspend fun contains(key: String) = false
            override suspend fun remove(key: String) = Unit
            override suspend fun removeWithPrefix(prefix: String) = Unit
        }
        assertSame(cancellation, assertFailsWith<CancellationException> {
            PlaudExportImporter.importInto(readResource("plaud_export_sample.json"), SecureDeviceKeyImporter(store))
        })
    }

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
        check(name == "plaud_export_sample.json") { "Missing test resource $name" }
        // Common tests use literal JSON; retain the resource bytes, including its final newline.
        return """
            {
              "version": 1,
              "source": "plaud_app_export",
              "device": {
                "serial": "881DEMO01",
                "snSignatureBase64": "AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK+2vcTL0tng5+71/AMKERgfJi00O0JJUFdeZWxzeoGIj5adpKuyucDHztXc4+rx+P8GDRQbIikwNz5FTFNaYWhvdn2Ei5KZoKeutbzDytHY3+bt9PsCCRAXHiUsMzpBSE9WXWRrcnmAh46VnKOqsbi/xs3U2+Lp8Pf+BQwTGiEoLzY9REtSWWBnbnV8g4qRmJ+mrbS7wsnQ197l7PP6AQgPFh0kKzI5QEdOVVxjanF4f4aNlJuiqbC3vsXM09rh6O/2/QQLEhkgJy41PENKUVhfZm10e4KJkJeepayzusHIz9bd5Ovy+Q==",
                "bindToken": "demo-user-token",
                "publicKeyPem": null,
                "privateKeyPem": null
              },
              "templates": [
                {
                  "name": "Demo custom",
                  "category": "meeting",
                  "systemPrompt": "Summarize briefly."
                }
              ]
            }
        """.trimIndent() + "\n"
    }
}
