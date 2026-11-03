package dev.velum.core.bundle

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SealedBundleTest {
    private var counter = 0
    private fun rnd(n: Int) = ByteArray(n) { (counter++ * 31 + 7).toByte() }
    private val fast = 1_000
    private val plain = "synthetic transcript: hello".encodeToByteArray()

    private fun seal(p: String = "correct horse", data: ByteArray = plain) = SealedBundle.seal(p, data, ::rnd, fast)

    @Test fun pbkdf2_matches_rfc7914_style_known_vector() {
        // PBKDF2-HMAC-SHA256("password", "salt", c=1) first 32 bytes (RFC 6070-style vector for SHA-256).
        val out = SealedBundle.pbkdf2("password".encodeToByteArray(), "salt".encodeToByteArray(), 1)
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", out.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
        val out2 = SealedBundle.pbkdf2("password".encodeToByteArray(), "salt".encodeToByteArray(), 4096)
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", out2.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
    }

    @Test fun round_trip_and_header_fields() {
        val s = seal()
        assertContentEquals("VLMB".encodeToByteArray(), s.copyOfRange(0, 4))
        assertEquals(SealedBundle.VERSION, s[4].toInt())
        assertContentEquals(plain, SealedBundle.open("correct horse", s))
        assertTrue(plain.decodeToString() !in s.decodeToString(), "plaintext must not appear")
    }

    @Test fun seal_is_randomized_per_call() {
        assertTrue(!seal().contentEquals(seal()))
    }

    @Test fun wrong_passphrase_fails() {
        assertFailsWith<BundleUnreadableException> { SealedBundle.open("wrong", seal()) }
        assertFailsWith<BundleUnreadableException> { SealedBundle.open("", seal()) }
    }

    @Test fun every_single_byte_flip_is_rejected() {
        val s = seal()
        for (i in s.indices) {
            val t = s.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<BundleUnreadableException>("byte $i") { SealedBundle.open("correct horse", t) }
        }
    }

    @Test fun truncation_extension_and_garbage_are_rejected() {
        val s = seal()
        assertFailsWith<BundleUnreadableException> { SealedBundle.open("correct horse", s.copyOf(s.size - 1)) }
        assertFailsWith<BundleUnreadableException> { SealedBundle.open("correct horse", s + byteArrayOf(0)) }
        assertFailsWith<BundleUnreadableException> { SealedBundle.open("correct horse", ByteArray(0)) }
        assertFailsWith<BundleUnreadableException> { SealedBundle.open("correct horse", ByteArray(200) { 1 }) }
    }

    @Test fun unknown_version_is_rejected() {
        val s = seal().also { it[4] = 2 }
        assertFailsWith<BundleUnreadableException> { SealedBundle.open("correct horse", s) }
    }

    @Test fun contents_round_trip_and_strict_decode() {
        val c = BundleContents(mapOf("notes" to "n".encodeToByteArray(), "templates" to ByteArray(0)))
        val d = BundleContents.decode(c.encode())
        assertEquals(setOf("notes", "templates"), d.sections.keys)
        assertContentEquals("n".encodeToByteArray(), d.sections.getValue("notes"))
        assertFailsWith<BundleUnreadableException> { BundleContents.decode(c.encode() + byteArrayOf(0)) }
        assertFailsWith<BundleUnreadableException> { BundleContents.decode(c.encode().copyOf(c.encode().size - 1)) }
    }

    @Test fun import_applies_nothing_if_any_section_fails_to_prepare() {
        val sealed = SealedBundle.seal("pw", BundleContents(mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2))).encode(), ::rnd, fast)
        val applied = mutableListOf<String>()
        val importers = mapOf(
            "a" to SectionImporter { { applied += "a" } },
            "b" to SectionImporter { error("bad b") },
        )
        assertFailsWith<IllegalStateException> { BundleImporter.import("pw", sealed, importers) }
        assertEquals(emptyList(), applied)
    }

    @Test fun import_rejects_wrong_passphrase_and_unknown_sections_without_applying() {
        val sealed = SealedBundle.seal("pw", BundleContents(mapOf("a" to byteArrayOf(1), "zzz" to byteArrayOf(2))).encode(), ::rnd, fast)
        val applied = mutableListOf<String>()
        val importers = mapOf("a" to SectionImporter { { applied += "a" } })
        assertFailsWith<BundleUnreadableException> { BundleImporter.import("nope", sealed, importers) }
        assertFailsWith<BundleUnreadableException> { BundleImporter.import("pw", sealed, importers) }
        assertEquals(emptyList(), applied)
    }

    @Test fun import_commits_in_sorted_order_when_all_prepare() {
        val sealed = SealedBundle.seal("pw", BundleContents(mapOf("b" to byteArrayOf(2), "a" to byteArrayOf(1))).encode(), ::rnd, fast)
        val applied = mutableListOf<String>()
        val importers = mapOf("a" to SectionImporter { d -> { applied += "a${d[0]}" } }, "b" to SectionImporter { d -> { applied += "b${d[0]}" } })
        BundleImporter.import("pw", sealed, importers)
        assertEquals(listOf("a1", "b2"), applied)
    }
}

class SealedBundleDefaultsTest {
    @Test fun default_iterations_round_trip() {
        val s = SealedBundle.seal("pw", byteArrayOf(1, 2, 3), { ByteArray(it) { i -> i.toByte() } })
        assertEquals(SealedBundle.DEFAULT_ITERATIONS, ((s[6].toInt() and 0xFF) shl 24) or ((s[7].toInt() and 0xFF) shl 16) or ((s[8].toInt() and 0xFF) shl 8) or (s[9].toInt() and 0xFF))
        assertContentEquals(byteArrayOf(1, 2, 3), SealedBundle.open("pw", s))
    }
}
