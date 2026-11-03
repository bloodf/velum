package dev.velum.core.bundle

import dev.velum.core.sync.Hlc
import dev.velum.core.sync.SyncRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleSectionsTest {
    private val now = 1_000_000_000_000L
    private val atomically: (() -> Unit) -> Unit = { it() }
    private var n = 0
    private fun rnd(c: Int) = ByteArray(c) { (n++ * 13 + 5).toByte() }

    private class Sink : BundleSink {
        val merged = mutableMapOf<String, Map<String, SyncRecord>>()
        var hashes: List<String>? = null
        override fun mergeRecords(section: String, records: Map<String, SyncRecord>) { merged[section] = records }
        override fun audioHashes(hashes: List<String>) { this.hashes = hashes }
    }

    private val transcript = SyncRecord().write("text", "hello", Hlc(now, 1, "node-a")).delete("title", Hlc(now, 2, "node-a"))
    private val hash = "a".repeat(64)

    private fun bundle(sections: Map<String, ByteArray>) =
        SealedBundle.seal("pw", BundleContents(sections).encode(), ::rnd, SealedBundle.MIN_ITERATIONS)

    @Test fun records_round_trip_including_tombstones_and_hlc() {
        val back = BundleSections.decodeRecords(BundleSections.encodeRecords(mapOf("rec-1" to transcript)))
        assertEquals(transcript, back.getValue("rec-1"))
        assertNull(back.getValue("rec-1").value("title"))
    }

    @Test fun full_bundle_round_trips_every_synced_type_and_audio_hashes() {
        val sections = BundleSections.recordSections.associateWith { BundleSections.encodeRecords(mapOf("id-$it" to transcript)) } +
            (BundleSections.AUDIO_HASHES to BundleSections.encodeAudioHashes(listOf(hash)))
        val sink = Sink()
        BundleImporter.import("pw", bundle(sections), BundleSections.importers(now, sink), atomically)
        assertEquals(BundleSections.recordSections.toSet(), sink.merged.keys)
        assertEquals(transcript, sink.merged.getValue(BundleSections.NOTES).getValue("id-notes"))
        assertEquals(listOf(hash), sink.hashes)
    }

    @Test fun settings_and_voice_profiles_are_not_importable() {
        for (name in listOf("settings", "voice-profiles")) {
            val sink = Sink()
            val sealed = bundle(mapOf(name to "{}".encodeToByteArray(), BundleSections.NOTES to BundleSections.encodeRecords(emptyMap())))
            assertFailsWith<BundleUnreadableException>(name) { BundleImporter.import("pw", sealed, BundleSections.importers(now, sink), atomically) }
            assertEquals(emptyMap(), sink.merged, name)
        }
    }

    @Test fun oversized_sections_are_refused_before_parsing() {
        val big = ByteArray(BundleSections.MAX_SECTION_BYTES + 1) { ' '.code.toByte() }
        assertFailsWith<BundleUnreadableException> { BundleSections.decodeRecords(big) }
        assertFailsWith<BundleUnreadableException> { BundleSections.decodeAudioHashes(big) }
    }

    @Test fun a_future_timestamp_rejects_the_whole_bundle_with_its_own_reason_and_no_merge() {
        val far = SyncRecord().write("text", "x", Hlc(now + 3L * 24 * 60 * 60 * 1000, 0, "evil"))
        val sink = Sink()
        val sealed = bundle(mapOf(
            BundleSections.NOTES to BundleSections.encodeRecords(mapOf("ok" to transcript)),
            BundleSections.TRANSCRIPTS to BundleSections.encodeRecords(mapOf("bad" to far)),
        ))
        val e = assertFailsWith<BundleClockSkewException> { BundleImporter.import("pw", sealed, BundleSections.importers(now, sink), atomically) }
        assertTrue(e !is BundleUnreadableException && "passphrase" !in e.message.orEmpty(), "must not say wrong passphrase")
        assertEquals(emptyMap(), sink.merged)
        assertNull(sink.hashes)
    }

    @Test fun malformed_oversized_and_out_of_range_payloads_are_unreadable() {
        for (bad in listOf("not json", """{"v":2,"records":{}}""", """{"v":1,"records":{},"extra":1}""",
            """{"v":1,"records":{"":{}}}""", """{"v":1,"records":{"a":{"f":{"value":"x","wallMs":-1,"counter":0,"node":"n"}}}}""",
            """{"v":1,"records":{"a":{"f":{"value":"x","wallMs":1,"counter":-1,"node":"n"}}}}""")) {
            assertFailsWith<BundleUnreadableException>(bad) { BundleSections.decodeRecords(bad.encodeToByteArray()) }
        }
        assertFailsWith<BundleUnreadableException> { BundleSections.decodeRecords(byteArrayOf(0xC3.toByte())) } // invalid UTF-8
        for (bad in listOf("""{"v":1,"hashes":["abc"]}""", """{"v":1,"hashes":["${"A".repeat(64)}"]}""", """{"v":1,"hashes":["${"g".repeat(64)}"]}""")) {
            assertFailsWith<BundleUnreadableException>(bad) { BundleSections.decodeAudioHashes(bad.encodeToByteArray()) }
        }
    }
}
