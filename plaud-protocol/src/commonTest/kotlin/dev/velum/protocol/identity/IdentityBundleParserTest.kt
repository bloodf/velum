package dev.velum.protocol.identity

import dev.velum.protocol.DeviceIdentityKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityBundleParserTest {
    @Test
    fun parse_valid_usb_route_bundle() {
        val raw = SyntheticIdentityFixtures.validBundleJson()
        assertTrue(raw.contains(SyntheticIdentityFixtures.TEST_ONLY_MARKER))
        val result = IdentityBundleParser.parse(raw)
        val ok = assertIs<IdentityBundleParseResult.Success>(result)
        assertEquals(IdentityBundleFormat.VERSION, ok.bundle.version)
        assertEquals(SyntheticIdentityFixtures.SOURCE_USB, ok.bundle.source)
        assertEquals(SyntheticIdentityFixtures.SERIAL, ok.bundle.serial)
        assertEquals(SyntheticIdentityFixtures.BIND_TOKEN, ok.bundle.bindToken)
        assertTrue(
            ok.bundle.serialSignature.contentEquals(SyntheticIdentityFixtures.signatureBytes()),
        )
        assertNull(ok.bundle.publicKeyPem)
        assertNull(ok.bundle.privateKeyPem)
    }

    @Test
    fun parse_valid_bundle_with_optional_pems_and_templates() {
        val raw = SyntheticIdentityFixtures.validBundleJson(
            includePems = true,
            source = SyntheticIdentityFixtures.SOURCE_EXPORT,
            templatesJson = """[{"name":"Demo","systemPrompt":"x"}]""",
        )
        val ok = assertIs<IdentityBundleParseResult.Success>(IdentityBundleParser.parse(raw))
        assertEquals(SyntheticIdentityFixtures.SOURCE_EXPORT, ok.bundle.source)
        assertEquals(SyntheticIdentityFixtures.PUBLIC_KEY_PEM, ok.bundle.publicKeyPem)
        assertEquals(SyntheticIdentityFixtures.PRIVATE_KEY_PEM, ok.bundle.privateKeyPem)
    }

    @Test
    fun rejects_empty_input() {
        val fail = assertIs<IdentityBundleParseResult.Failure>(IdentityBundleParser.parse(""))
        assertIs<IdentityBundleParseError.Empty>(fail.error)
        val blank = assertIs<IdentityBundleParseResult.Failure>(IdentityBundleParser.parse("   "))
        assertIs<IdentityBundleParseError.Empty>(blank.error)
    }

    @Test
    fun rejects_truncated_json() {
        val truncated = """{"version":1,"source":"usb_route","device":{"""
        val fail = assertIs<IdentityBundleParseResult.Failure>(
            IdentityBundleParser.parse(truncated),
        )
        assertIs<IdentityBundleParseError.Malformed>(fail.error)
        assertFalse(fail.error.userMessage().contains(truncated))
    }

    @Test
    fun rejects_oversized_document() {
        val padding = "x".repeat(IdentityBundleFormat.MAX_DOCUMENT_CHARS)
        val raw = SyntheticIdentityFixtures.validBundleJson() + padding
        assertTrue(raw.length > IdentityBundleFormat.MAX_DOCUMENT_CHARS)
        val fail = assertIs<IdentityBundleParseResult.Failure>(IdentityBundleParser.parse(raw))
        val oversized = assertIs<IdentityBundleParseError.Oversized>(fail.error)
        assertEquals(IdentityBundleFormat.MAX_DOCUMENT_CHARS, oversized.maxChars)
    }

    @Test
    fun rejects_wrong_version() {
        val raw = SyntheticIdentityFixtures.validBundleJson()
            .replace(
                "\"version\": ${IdentityBundleFormat.VERSION}",
                "\"version\": 99",
            )
        val fail = assertIs<IdentityBundleParseResult.Failure>(IdentityBundleParser.parse(raw))
        val unsupported = assertIs<IdentityBundleParseError.UnsupportedVersion>(fail.error)
        assertEquals(99, unsupported.found)
        assertTrue(fail.error.userMessage().contains("99"))
        assertTrue(fail.error.userMessage().contains("${IdentityBundleFormat.VERSION}"))
    }

    @Test
    fun rejects_garbage_input() {
        val garbageInputs = listOf(
            "not-json-at-all",
            "\u0000\u0001\u0002binary",
            "{]",
            "null",
            "[]",
            "\"string\"",
        )
        for (garbage in garbageInputs) {
            val fail = assertIs<IdentityBundleParseResult.Failure>(
                IdentityBundleParser.parse(garbage),
                "expected malformed for: $garbage",
            )
            assertIs<IdentityBundleParseError.Malformed>(fail.error)
            assertFalse(
                IdentityRedaction.containsSecret(
                    fail.toString(),
                    SyntheticIdentityFixtures.validBundle(),
                ),
            )
        }
    }

    @Test
    fun rejects_missing_required_fields() {
        val sig = SyntheticIdentityFixtures.signatureBase64()
        val cases = listOf(
            """{"version":1,"source":"usb_route"}""" to IdentityBundleFormat.FIELD_DEVICE,
            """{"version":1,"source":"usb_route","device":{"snSignatureBase64":"$sig","bindToken":"t"}}""" to
                IdentityBundleFormat.FIELD_SERIAL,
            """{"version":1,"source":"usb_route","device":{"serial":"s","bindToken":"t"}}""" to
                IdentityBundleFormat.FIELD_SN_SIGNATURE_BASE64,
            """{"version":1,"source":"usb_route","device":{"serial":"s","snSignatureBase64":"$sig","bindToken":""}}""" to
                IdentityBundleFormat.FIELD_BIND_TOKEN,
            """{"version":1,"source":"","device":{"serial":"s","snSignatureBase64":"$sig","bindToken":"t"}}""" to
                IdentityBundleFormat.FIELD_SOURCE,
        )
        for ((raw, field) in cases) {
            val fail = assertIs<IdentityBundleParseResult.Failure>(IdentityBundleParser.parse(raw))
            val missing = assertIs<IdentityBundleParseError.MissingField>(fail.error)
            assertEquals(field, missing.path, "raw=$raw")
            assertFalse(fail.error.userMessage().contains(sig))
        }
    }

    @Test
    fun rejects_wrong_signature_length_and_invalid_base64() {
        val shortSig = IdentityBase64.encode(ByteArray(16) { 1 })
        val rawShort = """
            {
              "version": 1,
              "source": "usb_route",
              "device": {
                "serial": "${SyntheticIdentityFixtures.SERIAL}",
                "snSignatureBase64": "$shortSig",
                "bindToken": "${SyntheticIdentityFixtures.BIND_TOKEN}"
              }
            }
        """.trimIndent()
        val failLen = assertIs<IdentityBundleParseResult.Failure>(
            IdentityBundleParser.parse(rawShort),
        )
        val invalidLen = assertIs<IdentityBundleParseError.InvalidField>(failLen.error)
        assertEquals(IdentityBundleFormat.FIELD_SN_SIGNATURE_BASE64, invalidLen.path)
        assertTrue(invalidLen.detail.contains("256"))
        assertFalse(failLen.error.userMessage().contains(shortSig))

        val rawBadB64 = """
            {
              "version": 1,
              "source": "usb_route",
              "device": {
                "serial": "${SyntheticIdentityFixtures.SERIAL}",
                "snSignatureBase64": "***not-base64***",
                "bindToken": "${SyntheticIdentityFixtures.BIND_TOKEN}"
              }
            }
        """.trimIndent()
        val failB64 = assertIs<IdentityBundleParseResult.Failure>(
            IdentityBundleParser.parse(rawBadB64),
        )
        val invalidB64 = assertIs<IdentityBundleParseError.InvalidField>(failB64.error)
        assertEquals(IdentityBundleFormat.FIELD_SN_SIGNATURE_BASE64, invalidB64.path)
        assertFalse(failB64.error.userMessage().contains("***not-base64***"))
    }

    @Test
    fun rejects_oversized_field_values() {
        val hugeSerial = "S".repeat(IdentityBundleFormat.MAX_SERIAL_CHARS + 1)
        val raw = """
            {
              "version": 1,
              "source": "usb_route",
              "device": {
                "serial": "$hugeSerial",
                "snSignatureBase64": "${SyntheticIdentityFixtures.signatureBase64()}",
                "bindToken": "${SyntheticIdentityFixtures.BIND_TOKEN}"
              }
            }
        """.trimIndent()
        val fail = assertIs<IdentityBundleParseResult.Failure>(IdentityBundleParser.parse(raw))
        val invalid = assertIs<IdentityBundleParseError.InvalidField>(fail.error)
        assertEquals(IdentityBundleFormat.FIELD_SERIAL, invalid.path)
        assertFalse(fail.error.userMessage().contains(hugeSerial))
    }

    @Test
    fun toString_and_errors_redact_identity_values() {
        val bundle = SyntheticIdentityFixtures.validBundle(includePems = true)
        val rendered = bundle.toString()
        assertTrue(rendered.contains(IdentityRedaction.PLACEHOLDER))
        for (secret in IdentityRedaction.secretSamples(bundle)) {
            assertFalse(
                rendered.contains(secret),
                "toString leaked a secret sample",
            )
        }

        val ok = assertIs<IdentityBundleParseResult.Success>(
            IdentityBundleParser.parse(SyntheticIdentityFixtures.validBundleJson(includePems = true)),
        )
        assertFalse(IdentityRedaction.containsSecret(ok.toString(), ok.bundle))

        // Force a failure whose input contains secrets; messages must not echo them.
        val poisoned = SyntheticIdentityFixtures.validBundleJson(includePems = true)
            .replace(
                "\"version\": ${IdentityBundleFormat.VERSION}",
                "\"version\": 2",
            )
        val fail = assertIs<IdentityBundleParseResult.Failure>(
            IdentityBundleParser.parse(poisoned),
        )
        assertNotNull(fail.error.userMessage())
        assertFalse(IdentityRedaction.containsSecret(fail.error.userMessage(), bundle))
        assertFalse(IdentityRedaction.containsSecret(fail.toString(), bundle))
        assertFalse(IdentityRedaction.containsSecret(fail.error.toString(), bundle))
    }

    @Test
    fun deviceIdentityKeys_toString_is_redacted() {
        val keys = DeviceIdentityKeys(
            serialNumber = SyntheticIdentityFixtures.SERIAL,
            serialSignature = SyntheticIdentityFixtures.signatureBytes(),
            publicKeyPem = SyntheticIdentityFixtures.PUBLIC_KEY_PEM,
            privateKeyPem = SyntheticIdentityFixtures.PRIVATE_KEY_PEM,
            bindToken = SyntheticIdentityFixtures.BIND_TOKEN,
        )
        val text = keys.toString()
        assertTrue(text.contains("[REDACTED]"))
        assertFalse(text.contains(SyntheticIdentityFixtures.SERIAL))
        assertFalse(text.contains(SyntheticIdentityFixtures.BIND_TOKEN))
        assertFalse(text.contains(SyntheticIdentityFixtures.PUBLIC_KEY_PEM))
        assertFalse(text.contains(SyntheticIdentityFixtures.PRIVATE_KEY_PEM))
        assertFalse(text.contains(SyntheticIdentityFixtures.signatureBase64()))
    }
}
