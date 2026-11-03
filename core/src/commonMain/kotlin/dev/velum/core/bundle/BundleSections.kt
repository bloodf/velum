package dev.velum.core.bundle

import dev.velum.core.sync.Hlc
import dev.velum.core.sync.SyncField
import dev.velum.core.sync.SyncRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Receives a fully validated import. Called only from the commit phase, inside the caller's transaction (#71). */
interface BundleSink {
    /** Merge [records] (id to per-field record) into local storage with [SyncRecord.mergeRemote] semantics. */
    fun mergeRecords(section: String, records: Map<String, SyncRecord>)

    /** SHA-256 hex digests of audio the sender holds. Audio bytes are never in a bundle. */
    fun audioHashes(hashes: List<String>)
}

/**
 * Maps the #69 synced data types onto [BundleContents] sections (#71). Decisions, stated plainly:
 * - Settings are NOT in the bundle (owner decision pending); a `settings` section is rejected as unknown.
 * - Audio is NOT in the bundle: only SHA-256 content hashes ([AUDIO_HASHES]), so the receiver knows what to fetch.
 * - Record sections carry [SyncRecord]s (per-field LWW with HLC and tombstones) keyed by record id, so importing
 *   merges instead of overwriting.
 * Nothing here is wired to the app yet: there is no caller, hence no file read or UI.
 */
object BundleSections {
    const val TRANSCRIPTS = "transcripts"
    const val NOTES = "notes"
    const val VOICE_PROFILES = "voice-profiles"
    const val TEMPLATES = "templates"
    const val AUDIO_HASHES = "audio-hashes"

    val recordSections: List<String> = listOf(TRANSCRIPTS, NOTES, VOICE_PROFILES, TEMPLATES)

    const val MAX_RECORDS = 100_000
    const val MAX_FIELDS_PER_RECORD = 1_000
    const val MAX_NAME_CHARS = 256
    private const val SHA256_HEX = 64

    @Serializable
    private class FieldWire(val value: String?, val wallMs: Long, val counter: Int, val node: String)

    @Serializable
    private class RecordsWire(val v: Int, val records: Map<String, Map<String, FieldWire>>)

    @Serializable
    private class HashesWire(val v: Int, val hashes: List<String>)

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encodeRecords(records: Map<String, SyncRecord>): ByteArray {
        val wire = RecordsWire(
            1,
            records.mapValues { (_, r) -> r.fields.mapValues { (_, f) -> FieldWire(f.value, f.hlc.wallMs, f.hlc.counter, f.hlc.node) } },
        )
        return json.encodeToString(wire).encodeToByteArray()
    }

    /** Strict decode; any malformed, oversized or out-of-range input is [BundleUnreadableException]. */
    fun decodeRecords(bytes: ByteArray): Map<String, SyncRecord> {
        val wire = parse<RecordsWire>(bytes)
        if (wire.v != 1 || wire.records.size > MAX_RECORDS) throw BundleUnreadableException()
        return wire.records.mapValues { (id, fields) ->
            if (id.isEmpty() || id.length > MAX_NAME_CHARS || fields.size > MAX_FIELDS_PER_RECORD) throw BundleUnreadableException()
            SyncRecord(
                fields.mapValues { (name, f) ->
                    if (name.isEmpty() || name.length > MAX_NAME_CHARS) throw BundleUnreadableException()
                    if (f.wallMs < 0 || f.counter < 0 || f.node.isEmpty() || f.node.length > MAX_NAME_CHARS) throw BundleUnreadableException()
                    SyncField(f.value, Hlc(f.wallMs, f.counter, f.node))
                },
            )
        }
    }

    fun encodeAudioHashes(hashes: List<String>): ByteArray = json.encodeToString(HashesWire(1, hashes)).encodeToByteArray()

    fun decodeAudioHashes(bytes: ByteArray): List<String> {
        val wire = parse<HashesWire>(bytes)
        if (wire.v != 1 || wire.hashes.size > MAX_RECORDS) throw BundleUnreadableException()
        if (!wire.hashes.all { h -> h.length == SHA256_HEX && h.all { it in '0'..'9' || it in 'a'..'f' } }) throw BundleUnreadableException()
        return wire.hashes
    }

    /**
     * Importers for [BundleImporter.import]. Each `prepare` decodes and validates its whole section (record sections also
     * reject any timestamp more than the drift limit ahead of [nowMs], rejecting the whole bundle); only commits touch [sink].
     */
    fun importers(nowMs: Long, sink: BundleSink): Map<String, SectionImporter> =
        recordSections.associateWith { section ->
            SectionImporter { data ->
                val records = decodeRecords(data)
                if (!records.values.all { it.isWithinDrift(nowMs) }) throw BundleUnreadableException()
                ({ sink.mergeRecords(section, records) })
            }
        } + (AUDIO_HASHES to SectionImporter { data ->
            val hashes = decodeAudioHashes(data)
            ({ sink.audioHashes(hashes) })
        })

    private inline fun <reified T> parse(bytes: ByteArray): T = try {
        json.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (_: Exception) { // serialization errors and the platform's invalid-UTF-8 exception alike
        throw BundleUnreadableException()
    }
}
