package dev.velum.core.sync

/** One field's latest write. A null [value] is a tombstone: the delete must win over older writes on the other device. */
data class SyncField(val value: String?, val hlc: Hlc) {
    /** Newer write wins; equal timestamps (same write seen twice) fall back to value order so the result never depends on argument order. */
    fun merge(other: SyncField): SyncField {
        val order = hlc.compareTo(other.hlc)
        return when {
            order > 0 -> this
            order < 0 -> other
            else -> if (compareValues(value, other.value) >= 0) this else other
        }
    }
}

/**
 * Per-field last-writer-wins record (transcript, notes, a profile name, a settings value ...).
 * [merge] is commutative, associative and idempotent, so devices can exchange records in any order,
 * any number of times, and converge.
 */
data class SyncRecord(val fields: Map<String, SyncField> = emptyMap()) {
    fun write(name: String, value: String?, hlc: Hlc): SyncRecord =
        copy(fields = fields + (name to (fields[name]?.merge(SyncField(value, hlc)) ?: SyncField(value, hlc))))

    fun delete(name: String, hlc: Hlc): SyncRecord = write(name, null, hlc)

    fun merge(other: SyncRecord): SyncRecord =
        SyncRecord(
            (fields.keys + other.fields.keys).associateWith { name ->
                listOfNotNull(fields[name], other.fields[name]).reduce(SyncField::merge)
            },
        )

    /**
     * False when any timestamp is more than [maxDriftMs] ahead of [nowMs]. A record received from a peer
     * must pass this before [merge], or one far-future write would win every field it touches forever.
     */
    fun isWithinDrift(nowMs: Long, maxDriftMs: Long = DEFAULT_MAX_DRIFT_MS): Boolean =
        fields.values.all { it.hlc.wallMs - nowMs <= maxDriftMs }

    /** Live value, or null when absent or deleted. */
    fun value(name: String): String? = fields[name]?.value
}
