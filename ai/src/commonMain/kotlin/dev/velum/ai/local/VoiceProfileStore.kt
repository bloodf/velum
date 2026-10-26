package dev.velum.ai.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Named voice profiles: embedding vectors + display names under models/voices/.
 * Cluster match uses cosine similarity.
 */
class VoiceProfileStore(
    private val root: ModelStoreDir,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val profilesDir get() = root.resolve("voices").resolve("profiles")
    private val cacheDir get() = root.resolve("voices").resolve("cluster-cache")

    fun listProfiles(): List<VoiceProfile> {
        if (!profilesDir.exists()) return emptyList()
        return profilesDir.listChildren()
            .filter { !it.isDirectory() && it.name.endsWith(".json") }
            .mapNotNull { file ->
                runCatching { json.decodeFromString<VoiceProfileFile>(file.readBytes().decodeToString()) }
                    .getOrNull()
                    ?.toProfile(file.resolveSiblingBin())
            }
    }

    fun upsert(displayName: String, embedding: FloatArray, id: String = slug(displayName)): VoiceProfile {
        profilesDir.createDirectories()
        val profile = VoiceProfile(id = id, displayName = displayName, embedding = embedding.copyOf())
        val meta = profilesDir.resolve("$id.json")
        val bin = profilesDir.resolve("$id.f32")
        meta.writeBytes(
            json.encodeToString(
                VoiceProfileFile(id = id, displayName = displayName, dim = embedding.size),
            ).encodeToByteArray(),
        )
        bin.writeBytes(floatsToBytes(embedding))
        return profile
    }

    fun match(
        embedding: FloatArray,
        threshold: Float = DEFAULT_MATCH_THRESHOLD,
    ): VoiceProfile? {
        var best: VoiceProfile? = null
        var bestScore = threshold
        for (profile in listProfiles()) {
            if (profile.embedding.size != embedding.size) continue
            val score = cosineSimilarity(embedding, profile.embedding)
            if (score >= bestScore) {
                bestScore = score
                best = profile
            }
        }
        return best
    }

    fun cacheClusterEmbedding(recordingId: String, speakerId: String, embedding: FloatArray) {
        val dir = cacheDir.resolve(recordingId)
        dir.createDirectories()
        dir.resolve("${slug(speakerId)}.f32").writeBytes(floatsToBytes(embedding))
    }

    fun cachedClusterEmbedding(recordingId: String, speakerId: String): FloatArray? {
        val file = cacheDir.resolve(recordingId).resolve("${slug(speakerId)}.f32")
        if (!file.exists() || file.length() == 0L) return null
        return bytesToFloats(file.readBytes())
    }

    /**
     * Persist a named profile from a cached cluster embedding and return it.
     */
    fun promoteCached(
        recordingId: String,
        speakerId: String,
        displayName: String,
    ): VoiceProfile? {
        val embedding = cachedClusterEmbedding(recordingId, speakerId) ?: return null
        return upsert(displayName = displayName, embedding = embedding)
    }

    companion object {
        const val DEFAULT_MATCH_THRESHOLD = 0.55f

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "embedding size mismatch ${a.size} vs ${b.size}" }
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                val x = a[i].toDouble()
                val y = b[i].toDouble()
                dot += x * y
                na += x * x
                nb += y * y
            }
            if (na == 0.0 || nb == 0.0) return 0f
            return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat()
        }

        fun slug(raw: String): String =
            raw.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "speaker" }

        fun floatsToBytes(values: FloatArray): ByteArray {
            val out = ByteArray(values.size * 4)
            for (i in values.indices) {
                val bits = values[i].toBits()
                val o = i * 4
                out[o] = (bits and 0xFF).toByte()
                out[o + 1] = ((bits ushr 8) and 0xFF).toByte()
                out[o + 2] = ((bits ushr 16) and 0xFF).toByte()
                out[o + 3] = ((bits ushr 24) and 0xFF).toByte()
            }
            return out
        }

        fun bytesToFloats(bytes: ByteArray): FloatArray {
            require(bytes.size % 4 == 0) { "embedding bytes not multiple of 4" }
            val out = FloatArray(bytes.size / 4)
            for (i in out.indices) {
                val o = i * 4
                val bits =
                    (bytes[o].toInt() and 0xFF) or
                        ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[o + 3].toInt() and 0xFF) shl 24)
                out[i] = Float.fromBits(bits)
            }
            return out
        }
    }
}

data class VoiceProfile(
    val id: String,
    val displayName: String,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is VoiceProfile && id == other.id && displayName == other.displayName &&
            embedding.contentEquals(other.embedding)

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + displayName.hashCode()) + embedding.contentHashCode()
}

@Serializable
private data class VoiceProfileFile(
    val id: String,
    val displayName: String,
    val dim: Int,
)

private fun VoiceProfileFile.toProfile(bin: ModelStoreDir): VoiceProfile? {
    if (!bin.exists()) return null
    val embedding = VoiceProfileStore.bytesToFloats(bin.readBytes())
    if (embedding.size != dim) return null
    return VoiceProfile(id = id, displayName = displayName, embedding = embedding)
}

private fun ModelStoreDir.resolveSiblingBin(): ModelStoreDir {
    val base = name.removeSuffix(".json")
    val parent = absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
    return if (parent.isEmpty()) ModelStoreDir("$base.f32")
    else ModelStoreDir("$parent/$base.f32")
}
