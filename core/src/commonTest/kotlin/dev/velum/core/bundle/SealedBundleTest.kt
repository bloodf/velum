package dev.velum.core.bundle

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SealedBundleTest {
    private var counter = 0
    private fun rnd(n: Int) = ByteArray(n) { (counter++ * 31 + 7).toByte() }
    private val fast = SealedBundle.MIN_ITERATIONS
    private val plain = "synthetic transcript: hello".encodeToByteArray()

    private fun seal(p: String = "correct horse", data: ByteArray = plain) = SealedBundle.seal(p, data, ::rnd, fast)

    private val atomically: (() -> Unit) -> Unit = { it() }

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test fun platform_pbkdf2_matches_known_vectors() {
        // PBKDF2-HMAC-SHA256, P="password", S="salt", dkLen=32.
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", hex(pbkdf2HmacSha256("password", "salt".encodeToByteArray(), 1, 32)))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", hex(pbkdf2HmacSha256("password", "salt".encodeToByteArray(), 4096, 32)))
    }

    @Test fun iteration_bounds_are_enforced_before_any_key_derivation() {
        assertFailsWith<IllegalArgumentException> { SealedBundle.seal("pw", plain, ::rnd, SealedBundle.MIN_ITERATIONS - 1) }
        assertFailsWith<IllegalArgumentException> { SealedBundle.seal("pw", plain, ::rnd, SealedBundle.MAX_ITERATIONS + 1) }
        val s = seal("pw")
        // Tampered counts: 1 (trivially weak), above the ceiling, and Int.MAX (would run for minutes if derived first).
        for (count in listOf(1, SealedBundle.MIN_ITERATIONS - 1, SealedBundle.MAX_ITERATIONS + 1, Int.MAX_VALUE, -1)) {
            val t = s.copyOf()
            t[6] = (count ushr 24).toByte(); t[7] = (count ushr 16).toByte(); t[8] = (count ushr 8).toByte(); t[9] = count.toByte()
            assertFailsWith<BundleUnreadableException>("count $count") { SealedBundle.open("pw", t) }
        }
    }

    @Test fun nfc_vector_and_equivalent_unicode_forms_open_the_same_bundle() {
        // "café" (NFC), salt "salt", 1000 iterations.
        assertEquals("5396cb973d99ae225f9113732d095024ed7f033f3b1b47bcd3b58b3f7ed5f73d", hex(pbkdf2HmacSha256("caf\u00e9", "salt".encodeToByteArray(), 1000, 32)))
        assertEquals("caf\u00e9", normalizeNfc("cafe\u0301"))
        val s = seal("caf\u00e9")
        assertContentEquals(plain, SealedBundle.open("cafe\u0301", s)) // NFD typed on another platform
    }

    @Test fun lone_surrogates_are_rejected() {
        assertFailsWith<IllegalArgumentException> { seal("bad\uD800pass") }
        assertFailsWith<IllegalArgumentException> { seal("bad\uDC00pass") }
        assertFailsWith<BundleUnreadableException> { SealedBundle.open("bad\uD800pass", seal()) }
        seal("emoji \uD83D\uDE00 ok") // a valid pair is fine
    }

    @Test fun oversized_bundles_are_refused_before_any_work() {
        assertFailsWith<BundleUnreadableException> { SealedBundle.open("pw", ByteArray(SealedBundle.MAX_BUNDLE_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> { SealedBundle.seal("pw", ByteArray(SealedBundle.MAX_BUNDLE_BYTES), ::rnd, fast) }
    }

    @Test fun secure_random_returns_requested_distinct_bytes() {
        val a = secureRandomBytes(16)
        val b = secureRandomBytes(16)
        assertEquals(16, a.size)
        assertTrue(!a.contentEquals(b) && a.any { it.toInt() != 0 })
    }

    @Test fun hostile_section_length_and_too_many_sections_fail_cleanly() {
        // count=1, nameLen=1, name "a", dataLen=Int.MAX_VALUE with no data: pos + n would overflow without the guard.
        val hostile = byteArrayOf(0, 0, 0, 1, 1, 'a'.code.toByte(), 0x7F, -1, -1, -1)
        assertFailsWith<BundleUnreadableException> { BundleContents.decode(hostile) }
        val nearMax = byteArrayOf(0, 0, 0, 1, 1, 'a'.code.toByte(), 0x7F, -1, -1, -5) // length near Int.MAX with few bytes left
        assertFailsWith<BundleUnreadableException> { BundleContents.decode(nearMax) }
        assertFailsWith<IllegalArgumentException> { BundleContents((0..BundleContents.MAX_SECTIONS).associate { "s$it" to ByteArray(0) }).encode() }
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

    @Test fun header_and_sampled_body_and_tag_byte_flips_are_rejected() {
        val s = seal()
        // Only salt/nonce/body/tag flips reach the KDF (about a second each), so sample those; magic, version, kdf and
        // iteration bytes are rejected before it and are all covered.
        for (i in (0 until 10) + listOf(10, 25, 37, 38, s.size / 2, s.size - 17, s.size - 16, s.size - 1)) {
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
        assertFailsWith<IllegalStateException> { BundleImporter.import("pw", sealed, importers, atomically) }
        assertEquals(emptyList(), applied)
    }

    @Test fun import_rejects_wrong_passphrase_and_unknown_sections_without_applying() {
        val sealed = SealedBundle.seal("pw", BundleContents(mapOf("a" to byteArrayOf(1), "zzz" to byteArrayOf(2))).encode(), ::rnd, fast)
        val applied = mutableListOf<String>()
        val importers = mapOf("a" to SectionImporter { { applied += "a" } })
        assertFailsWith<BundleUnreadableException> { BundleImporter.import("nope", sealed, importers, atomically) }
        assertFailsWith<BundleUnreadableException> { BundleImporter.import("pw", sealed, importers, atomically) }
        assertEquals(emptyList(), applied)
    }

    @Test fun import_commits_in_sorted_order_when_all_prepare() {
        val sealed = SealedBundle.seal("pw", BundleContents(mapOf("b" to byteArrayOf(2), "a" to byteArrayOf(1))).encode(), ::rnd, fast)
        val applied = mutableListOf<String>()
        val importers = mapOf("a" to SectionImporter { d -> { applied += "a${d[0]}" } }, "b" to SectionImporter { d -> { applied += "b${d[0]}" } })
        BundleImporter.import("pw", sealed, importers, atomically)
        assertEquals(listOf("a1", "b2"), applied)
    }
}

class SealedBundleDefaultsTest {
    @Test fun default_iterations_round_trip() {
        val s = SealedBundle.seal("pw", byteArrayOf(1, 2, 3)) // public path: platform CSPRNG, default iterations
        assertEquals(SealedBundle.DEFAULT_ITERATIONS, ((s[6].toInt() and 0xFF) shl 24) or ((s[7].toInt() and 0xFF) shl 16) or ((s[8].toInt() and 0xFF) shl 8) or (s[9].toInt() and 0xFF))
        assertContentEquals(byteArrayOf(1, 2, 3), SealedBundle.open("pw", s))
    }
}
