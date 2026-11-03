package dev.velum.core.bundle

import dev.velum.core.sync.Hlc
import dev.velum.core.sync.SyncField
import dev.velum.core.sync.SyncRecord
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A record carries a timestamp too far ahead of this device's clock. Not a passphrase or damage problem: fix the clock and retry. */
class BundleClockSkewException : IllegalArgumentException(
    "The bundle has timestamps ahead of this device's clock. Check the date and time on both devices and try again.",
)

/**
 * Receives a fully validated import. Called only from the commit phase, inside the caller's transaction (#71).
 *
 * Contract for implementers:
 * - Merge with [SyncRecord.mergeRemote] using the SAME `nowMs` given to [BundleSections.importers], and fail (throw, so the
 *   transaction rolls back) when it returns null.
 * - Allowlist the fields each section may write; ignore every other field name. Never merge device-local fields such as
 *   `local_path` or `device_serial`, whatever the bundle says.
 * - Decoding checks shape and size only; it does not know what any field means.
 */
interface BundleSink {
    /** Merge [records] (id to per-field record) into local storage with [SyncRecord.mergeRemote] semantics. */
    fun mergeRecords(section: String, records: Map<String, SyncRecord>)

    /** SHA-256 hex digests of audio the sender holds. Audio bytes are never in a bundle. */
    fun audioHashes(hashes: List<String>)
}

/**
 * Maps the #69 synced data types onto [BundleContents] sections (#71). Decisions, stated plainly:
 * - Settings and voice profiles are NOT in the bundle (owner decisions pending): speaker embeddings are biometric-like,
 *   a bundle protects them only with its passphrase, and docs/PRIVACY.md says they stay on device. `settings` and
 *   `voice-profiles` sections are rejected as unknown.
 * - Audio is NOT in the bundle: only SHA-256 content hashes ([AUDIO_HASHES]), so the receiver knows what to fetch.
 * - Record sections carry [SyncRecord]s (per-field LWW with HLC and tombstones) keyed by record id, so importing
 *   merges instead of overwriting.
 * Nothing here is wired to the app yet: there is no caller, hence no file read or UI.
 */
object BundleSections {
    const val TRANSCRIPTS = "transcripts"
    const val NOTES = "notes"
    const val TEMPLATES = "templates"
    const val AUDIO_HASHES = "audio-hashes"

    val recordSections: List<String> = listOf(TRANSCRIPTS, NOTES, TEMPLATES)

    /** A section larger than this is refused before parsing: the parser expands JSON to several times its size on the heap. */
    const val MAX_SECTION_BYTES = 16 * 1024 * 1024
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

    /** Same limits as [decodeRecords], enforced here so an oversized section fails at export, not as "damaged" on import. */
    fun encodeRecords(records: Map<String, SyncRecord>): ByteArray {
        require(records.size <= MAX_RECORDS) { "too many records: ${records.size} (max $MAX_RECORDS)" }
        records.forEach { (id, r) ->
            require(id.isNotEmpty() && id.length <= MAX_NAME_CHARS) { "record id must be 1..$MAX_NAME_CHARS chars" }
            require(r.fields.size <= MAX_FIELDS_PER_RECORD) { "record $id has too many fields (max $MAX_FIELDS_PER_RECORD)" }
            r.fields.forEach { (name, f) ->
                require(name.isNotEmpty() && name.length <= MAX_NAME_CHARS) { "field name must be 1..$MAX_NAME_CHARS chars" }
                require(f.hlc.wallMs >= 0 && f.hlc.counter >= 0 && f.hlc.node.isNotEmpty() && f.hlc.node.length <= MAX_NAME_CHARS) {
                    "field $name has an invalid timestamp"
                }
            }
        }
        val wire = RecordsWire(
            1,
            records.mapValues { (_, r) -> r.fields.mapValues { (_, f) -> FieldWire(f.value, f.hlc.wallMs, f.hlc.counter, f.hlc.node) } },
        )
        return json.encodeToString(wire).encodeToByteArray().also { requireSectionFits(it) }
    }

    private fun requireSectionFits(bytes: ByteArray) =
        require(bytes.size <= MAX_SECTION_BYTES) { "section is ${bytes.size} bytes, over the ${MAX_SECTION_BYTES} byte limit; split the export" }

    /**
     * Shape and size checks: at most [MAX_SECTION_BYTES] (before parsing), version 1, no unknown keys, bounded counts and
     * lengths, non-negative HLC parts. Not checked: duplicate JSON keys (the last one wins) and what field names mean.
     * Anything malformed is [BundleUnreadableException].
     */
    fun decodeRecords(bytes: ByteArray): Map<String, SyncRecord> {
        if (bytes.size > MAX_SECTION_BYTES) throw BundleUnreadableException()
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

    fun encodeAudioHashes(hashes: List<String>): ByteArray {
        require(hashes.size <= MAX_RECORDS) { "too many hashes: ${hashes.size} (max $MAX_RECORDS)" }
        require(hashes.all(::isSha256Hex)) { "audio hashes must be 64 lowercase hex characters" }
        return json.encodeToString(HashesWire(1, hashes)).encodeToByteArray().also { requireSectionFits(it) }
    }

    private fun isSha256Hex(h: String) = h.length == SHA256_HEX && h.all { it in '0'..'9' || it in 'a'..'f' }

    fun decodeAudioHashes(bytes: ByteArray): List<String> {
        if (bytes.size > MAX_SECTION_BYTES) throw BundleUnreadableException()
        val wire = parse<HashesWire>(bytes)
        if (wire.v != 1 || wire.hashes.size > MAX_RECORDS) throw BundleUnreadableException()
        if (!wire.hashes.all(::isSha256Hex)) throw BundleUnreadableException()
        return wire.hashes
    }

    /**
     * Importers for [BundleImporter.import]. Each `prepare` decodes and validates its whole section (record sections also
     * reject any timestamp more than the drift limit ahead of [nowMs] with [BundleClockSkewException], rejecting the whole bundle); only commits touch [sink].
     */
    // ponytail: no total parsed-bytes budget across sections. Each section is capped at MAX_SECTION_BYTES and the whole
    // bundle at SealedBundle.MAX_BUNDLE_BYTES (64 MiB), so decoded objects are bounded by roughly 300 MB (estimate, not
    // measured). Measure that on a device and add a total budget (or lower MAX_BUNDLE_BYTES) before a caller is wired.
    fun importers(nowMs: Long, sink: BundleSink): Map<String, SectionImporter> =
        recordSections.associateWith { section ->
            SectionImporter { data ->
                val records = decodeRecords(data)
                if (!records.values.all { it.isWithinDrift(nowMs) }) throw BundleClockSkewException()
                ({ sink.mergeRecords(section, records) })
            }
        } + (AUDIO_HASHES to SectionImporter { data ->
            val hashes = decodeAudioHashes(data)
            ({ sink.audioHashes(hashes) })
        })

    private inline fun <reified T> parse(bytes: ByteArray): T = try {
        json.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (_: SerializationException) {
        throw BundleUnreadableException()
    } catch (_: IllegalArgumentException) {
        throw BundleUnreadableException()
    } catch (_: CharacterCodingException) {
        throw BundleUnreadableException()
    }
}
