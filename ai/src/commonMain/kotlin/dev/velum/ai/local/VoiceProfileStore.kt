package dev.velum.ai.local

import dev.velum.core.security.SecureStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Named voice profiles with encrypted embeddings, multi-sample centroids, and metadata.
 * Profiles never leave the device unless the user exports; [delete] / [wipeAll] remove blobs.
 */
@OptIn(ExperimentalEncodingApi::class)
class VoiceProfileStore(
    private val root: ModelStoreDir,
    private val secureStore: SecureStore? = null,
    private val cipherOverride: ProfileCipher? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val profilesDir get() = root.resolve("voices").resolve("profiles")
    private val cacheDir get() = root.resolve("voices").resolve("cluster-cache")

    private fun cipher(): ProfileCipher {
        cipherOverride?.let { return it }
        val key = loadOrCreateKey()
        return createProfileCipher(key)
    }

    private fun loadOrCreateKey(): ByteArray {
        val store = secureStore
        if (store == null) {
            // Deterministic test/desktop fallback — embeddings still AES-GCM-wrapped with a
            // local file key under models/voices/.key when SecureStore is absent.
            val keyFile = root.resolve("voices").resolve(".profile_key")
            if (keyFile.exists() && keyFile.length() == 32L) return keyFile.readBytes()
            root.resolve("voices").createDirectories()
            val generated = ByteArray(32) { (it * 17 + 41).toByte() }
            keyFile.writeBytes(generated)
            return generated
        }
        // Synchronous path: key must already be primed via [ensureKey].
        // Never fall back to a fixed key — that would encrypt profiles under a
        // known constant until ensureKey races in, then fail to decrypt.
        return primedKey
            ?: throw IllegalStateException(
                "VoiceProfileStore key not primed. Call ensureKey() before cipher use.",
            )
    }

    @Volatile
    private var primedKey: ByteArray? = null

    /**
     * Call once at app start (suspend) so Android Keystore-backed SecureStore can mint the key.
     */
    suspend fun ensureKey() {
        val store = secureStore ?: return
        val existing = store.getString(PROFILE_KEY_SECURE)
        if (!existing.isNullOrBlank()) {
            primedKey = Base64.decode(existing)
            return
        }
        val generated = ByteArray(32) { kotlin.random.Random.nextInt(0, 256).toByte() }
        store.putString(PROFILE_KEY_SECURE, Base64.encode(generated))
        primedKey = generated
    }

    fun listProfiles(): List<VoiceProfile> {
        if (!profilesDir.exists()) return emptyList()
        return profilesDir.listChildren()
            .filter { !it.isDirectory() && it.name.endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    json.decodeFromString<VoiceProfileFile>(file.readBytes().decodeToString())
                        .toProfile(file.resolveSiblingEnc(), cipher())
                }.getOrNull()
            }
            .sortedBy { it.displayName.lowercase() }
    }

    fun get(id: String): VoiceProfile? = listProfiles().firstOrNull { it.id == id }

    fun upsert(
        displayName: String,
        embedding: FloatArray,
        id: String = slug(displayName),
        role: String = "",
        company: String = "",
        email: String = "",
        notes: String = "",
        contactUri: String? = null,
    ): VoiceProfile {
        val existing = get(id)
        val samples = (existing?.sampleEmbeddings?.toMutableList() ?: mutableListOf())
        samples += embedding.copyOf()
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
        val centroid = averageEmbeddings(samples)
        val profile = VoiceProfile(
            id = id,
            displayName = displayName.trim().ifBlank { existing?.displayName ?: id },
            embedding = centroid,
            sampleEmbeddings = samples,
            role = role.ifBlank { existing?.role.orEmpty() },
            company = company.ifBlank { existing?.company.orEmpty() },
            email = email.ifBlank { existing?.email.orEmpty() },
            notes = notes.ifBlank { existing?.notes.orEmpty() },
            contactUri = contactUri ?: existing?.contactUri,
            sampleCount = samples.size,
        )
        writeProfile(profile)
        return profile
    }

    fun rename(id: String, displayName: String): VoiceProfile? {
        val existing = get(id) ?: return null
        val updated = existing.copy(displayName = displayName.trim().ifBlank { existing.displayName })
        writeProfile(updated)
        return updated
    }

    fun updateMeta(
        id: String,
        role: String? = null,
        company: String? = null,
        email: String? = null,
        notes: String? = null,
        contactUri: String? = null,
    ): VoiceProfile? {
        val existing = get(id) ?: return null
        val updated = existing.copy(
            role = role ?: existing.role,
            company = company ?: existing.company,
            email = email ?: existing.email,
            notes = notes ?: existing.notes,
            contactUri = contactUri ?: existing.contactUri,
        )
        writeProfile(updated)
        return updated
    }

    fun delete(id: String) {
        profilesDir.resolve("$id.json").deleteRecursively()
        profilesDir.resolve("$id.enc").deleteRecursively()
        // Legacy plaintext
        profilesDir.resolve("$id.f32").deleteRecursively()
    }

    fun wipeAll() {
        if (profilesDir.exists()) profilesDir.deleteRecursively()
        profilesDir.createDirectories()
    }

    fun match(
        embedding: FloatArray,
        threshold: Float = DEFAULT_MATCH_THRESHOLD,
    ): VoiceMatch? {
        var best: VoiceMatch? = null
        for (profile in listProfiles()) {
            if (profile.embedding.size != embedding.size) continue
            val score = cosineSimilarity(embedding, profile.embedding)
            if (score >= threshold && (best == null || score > best.confidence)) {
                best = VoiceMatch(profile = profile, confidence = score)
            }
        }
        return best
    }

    /** Confirm a match — averages the new sample into the centroid. */
    fun refine(id: String, embedding: FloatArray): VoiceProfile? {
        val existing = get(id) ?: return null
        return upsert(
            displayName = existing.displayName,
            embedding = embedding,
            id = id,
            role = existing.role,
            company = existing.company,
            email = existing.email,
            notes = existing.notes,
            contactUri = existing.contactUri,
        )
    }

    fun merge(intoId: String, fromId: String): VoiceProfile? {
        if (intoId == fromId) return get(intoId)
        val into = get(intoId) ?: return null
        val from = get(fromId) ?: return null
        if (into.embedding.size != from.embedding.size) return null
        val samples = (into.sampleEmbeddings + from.sampleEmbeddings).takeLast(MAX_SAMPLES)
        val merged = into.copy(
            embedding = averageEmbeddings(samples.ifEmpty { listOf(into.embedding, from.embedding) }),
            sampleEmbeddings = samples.ifEmpty { listOf(into.embedding, from.embedding) },
            sampleCount = samples.size.coerceAtLeast(2),
            notes = listOf(into.notes, from.notes).filter { it.isNotBlank() }.joinToString("\n"),
        )
        writeProfile(merged)
        delete(fromId)
        return merged
    }

    fun cacheClusterEmbedding(recordingId: String, speakerId: String, embedding: FloatArray) {
        val dir = cacheDir.resolve(recordingId)
        dir.createDirectories()
        val enc = cipher().encrypt(floatsToBytes(embedding))
        dir.resolve("${slug(speakerId)}.enc").writeBytes(enc)
        // Keep legacy plaintext cache for one release of promoteCached compatibility.
        dir.resolve("${slug(speakerId)}.f32").writeBytes(floatsToBytes(embedding))
    }

    fun cachedClusterEmbedding(recordingId: String, speakerId: String): FloatArray? {
        val encFile = cacheDir.resolve(recordingId).resolve("${slug(speakerId)}.enc")
        if (encFile.exists() && encFile.length() > 0L) {
            return runCatching {
                bytesToFloats(cipher().decrypt(encFile.readBytes()))
            }.getOrNull()
        }
        val file = cacheDir.resolve(recordingId).resolve("${slug(speakerId)}.f32")
        if (!file.exists() || file.length() == 0L) return null
        return bytesToFloats(file.readBytes())
    }

    fun promoteCached(
        recordingId: String,
        speakerId: String,
        displayName: String,
        role: String = "",
        company: String = "",
        email: String = "",
        notes: String = "",
        contactUri: String? = null,
    ): VoiceProfile? {
        val embedding = cachedClusterEmbedding(recordingId, speakerId) ?: return null
        return upsert(
            displayName = displayName,
            embedding = embedding,
            role = role,
            company = company,
            email = email,
            notes = notes,
            contactUri = contactUri,
        )
    }

    /** Encrypted export JSON (meta + base64 ciphertext embeddings). */
    fun exportEncrypted(): String {
        val c = cipher()
        val payload = listProfiles().map { p ->
            ExportedProfile(
                id = p.id,
                displayName = p.displayName,
                role = p.role,
                company = p.company,
                email = p.email,
                notes = p.notes,
                contactUri = p.contactUri,
                dim = p.embedding.size,
                sampleCount = p.sampleCount,
                embeddingB64 = Base64.encode(c.encrypt(floatsToBytes(p.embedding))),
                samplesB64 = p.sampleEmbeddings.map { Base64.encode(c.encrypt(floatsToBytes(it))) },
            )
        }
        return json.encodeToString(ExportedBundle(version = 1, profiles = payload))
    }

    fun importEncrypted(text: String): Int {
        val bundle = json.decodeFromString<ExportedBundle>(text)
        val c = cipher()
        var count = 0
        bundle.profiles.forEach { e ->
            val embedding = bytesToFloats(c.decrypt(Base64.decode(e.embeddingB64)))
            val samples = e.samplesB64.map { bytesToFloats(c.decrypt(Base64.decode(it))) }
                .ifEmpty { listOf(embedding) }
            writeProfile(
                VoiceProfile(
                    id = e.id.ifBlank { slug(e.displayName) },
                    displayName = e.displayName,
                    embedding = embedding,
                    sampleEmbeddings = samples,
                    role = e.role,
                    company = e.company,
                    email = e.email,
                    notes = e.notes,
                    contactUri = e.contactUri,
                    sampleCount = samples.size,
                ),
            )
            count++
        }
        return count
    }

    private fun writeProfile(profile: VoiceProfile) {
        profilesDir.createDirectories()
        val meta = profilesDir.resolve("${profile.id}.json")
        val enc = profilesDir.resolve("${profile.id}.enc")
        meta.writeBytes(
            json.encodeToString(
                VoiceProfileFile(
                    id = profile.id,
                    displayName = profile.displayName,
                    dim = profile.embedding.size,
                    role = profile.role,
                    company = profile.company,
                    email = profile.email,
                    notes = profile.notes,
                    contactUri = profile.contactUri,
                    sampleCount = profile.sampleCount,
                    encrypted = true,
                ),
            ).encodeToByteArray(),
        )
        val packed = packSamples(profile.sampleEmbeddings.ifEmpty { listOf(profile.embedding) })
        enc.writeBytes(cipher().encrypt(packed))
        // Remove legacy plaintext if present.
        profilesDir.resolve("${profile.id}.f32").deleteRecursively()
    }

    companion object {
        const val DEFAULT_MATCH_THRESHOLD = 0.55f
        const val MAX_SAMPLES = 8
        const val PROFILE_KEY_SECURE = "voice.profile_aes_key_b64"

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

        fun averageEmbeddings(samples: List<FloatArray>): FloatArray {
            require(samples.isNotEmpty())
            val dim = samples.first().size
            val out = FloatArray(dim)
            for (s in samples) {
                require(s.size == dim)
                for (i in 0 until dim) out[i] += s[i]
            }
            val n = samples.size.toFloat()
            for (i in 0 until dim) out[i] /= n
            return out
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

        private fun packSamples(samples: List<FloatArray>): ByteArray {
            val dim = samples.first().size
            val header = ByteArray(8)
            header[0] = (samples.size and 0xFF).toByte()
            header[1] = ((samples.size ushr 8) and 0xFF).toByte()
            header[2] = (dim and 0xFF).toByte()
            header[3] = ((dim ushr 8) and 0xFF).toByte()
            header[4] = ((dim ushr 16) and 0xFF).toByte()
            header[5] = ((dim ushr 24) and 0xFF).toByte()
            val body = samples.flatMap { floatsToBytes(it).asIterable() }.toByteArray()
            return header + body
        }

        private fun unpackSamples(bytes: ByteArray): List<FloatArray> {
            if (bytes.size < 8) return listOf(bytesToFloats(bytes))
            val count = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            val dim = (bytes[2].toInt() and 0xFF) or
                ((bytes[3].toInt() and 0xFF) shl 8) or
                ((bytes[4].toInt() and 0xFF) shl 16) or
                ((bytes[5].toInt() and 0xFF) shl 24)
            if (count <= 0 || dim <= 0) return listOf(bytesToFloats(bytes))
            val expected = 8 + count * dim * 4
            if (bytes.size < expected) return listOf(bytesToFloats(bytes))
            return (0 until count).map { i ->
                val start = 8 + i * dim * 4
                bytesToFloats(bytes.copyOfRange(start, start + dim * 4))
            }
        }
    }

    private fun VoiceProfileFile.toProfile(encFile: ModelStoreDir, cipher: ProfileCipher): VoiceProfile? {
        val samples: List<FloatArray> = when {
            encFile.exists() -> {
                val plain = cipher.decrypt(encFile.readBytes())
                unpackSamples(plain)
            }
            else -> {
                // Migrate legacy plaintext .f32
                val legacy = ModelStoreDir(
                    encFile.absolutePath.removeSuffix(".enc") + ".f32",
                )
                if (!legacy.exists()) return null
                listOf(bytesToFloats(legacy.readBytes()))
            }
        }
        if (samples.isEmpty()) return null
        val centroid = averageEmbeddings(samples)
        if (centroid.size != dim) return null
        return VoiceProfile(
            id = id,
            displayName = displayName,
            embedding = centroid,
            sampleEmbeddings = samples,
            role = role,
            company = company,
            email = email,
            notes = notes,
            contactUri = contactUri,
            sampleCount = samples.size,
        )
    }
}

data class VoiceMatch(
    val profile: VoiceProfile,
    val confidence: Float,
)

data class VoiceProfile(
    val id: String,
    val displayName: String,
    val embedding: FloatArray,
    val sampleEmbeddings: List<FloatArray> = emptyList(),
    val role: String = "",
    val company: String = "",
    val email: String = "",
    val notes: String = "",
    val contactUri: String? = null,
    val sampleCount: Int = 1,
) {
    fun creditLine(): String = buildString {
        append(displayName)
        if (role.isNotBlank()) append(" · ").append(role)
        if (company.isNotBlank()) append(" @ ").append(company)
    }

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
    val role: String = "",
    val company: String = "",
    val email: String = "",
    val notes: String = "",
    val contactUri: String? = null,
    val sampleCount: Int = 1,
    val encrypted: Boolean = false,
)

@Serializable
private data class ExportedBundle(
    val version: Int = 1,
    val profiles: List<ExportedProfile> = emptyList(),
)

@Serializable
private data class ExportedProfile(
    val id: String,
    val displayName: String,
    val role: String = "",
    val company: String = "",
    val email: String = "",
    val notes: String = "",
    val contactUri: String? = null,
    val dim: Int,
    val sampleCount: Int = 1,
    val embeddingB64: String,
    val samplesB64: List<String> = emptyList(),
)

private fun ModelStoreDir.resolveSiblingEnc(): ModelStoreDir {
    val base = name.removeSuffix(".json")
    val parent = absolutePath.substringBeforeLast('/', missingDelimiterValue = "")
    return if (parent.isEmpty()) ModelStoreDir("$base.enc")
    else ModelStoreDir("$parent/$base.enc")
}
