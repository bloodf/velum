package dev.velum.ai.local

import dev.velum.core.security.SecureStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Named voice profiles with encrypted embeddings, multi-sample centroids, and metadata.
 * Profiles never leave the device unless the user exports; [delete] / [wipeAll] remove blobs.
 *
 * **Key material:** production always constructs this store with a real [SecureStore]
 * (Android Keystore / desktop secure prefs). There is no file-key fallback in production
 * sources — tests pass [InMemorySecureStore][dev.velum.core.security.InMemorySecureStore]
 * or a [cipherOverride]. Cipher operations are `suspend` and await [ensureKey] so callers
 * never observe a "key not primed" race.
 */
@OptIn(ExperimentalEncodingApi::class)
class VoiceProfileStore(
    private val root: ModelStoreDir,
    private val secureStore: SecureStore,
    private val cipherOverride: ProfileCipher? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val profilesDir get() = root.resolve("voices").resolve("profiles")
    private val cacheDir get() = root.resolve("voices").resolve("cluster-cache")
    private val legacyKeyFile get() = root.resolve("voices").resolve(".profile_key")
    private val legacyPurgedMarker get() = root.resolve("voices").resolve(".profile_key.purged")

    private val keyMutex = Mutex()

    @Volatile
    private var primedKey: ByteArray? = null

    private val _primingState =
        MutableStateFlow<VoiceKeyPrimingState>(VoiceKeyPrimingState.Unprimed)
    val primingState: StateFlow<VoiceKeyPrimingState> = _primingState.asStateFlow()

    /**
     * Idempotent key priming. Concurrent callers share one attempt under [keyMutex].
     * A failure does not stick: [primedKey] stays null, [primingState] becomes
     * [VoiceKeyPrimingState.Failed], and the next call retries.
     *
     * Fast path is taken only when [primingState] is already [VoiceKeyPrimingState.Ready]
     * so readers never observe a key mid-purge. [primedKey] is published only after any
     * legacy purge finishes; cancellation mid-purge leaves the store Unprimed/retryable.
     *
     * A new key is minted only when SecureStore confirms the slot was never written
     * (`getString` null and `contains` false). If the key exists but cannot be read,
     * priming fails with [VoiceProfileKeyUnreadableException] — never overwrites the
     * key and never deletes profiles.
     *
     * Legacy purge runs only when this prime minted a brand-new key, `voices/.profile_key`
     * still exists, and no `.profile_key.purged` marker is present. It deletes only
     * profiles that decrypt under the legacy file key (provably legacy-key blobs);
     * corrupt JSON, missing `.enc`, and dimension mismatches are kept. File I/O runs
     * on [Dispatchers.IO]. Never logs key material.
     */
    suspend fun ensureKey() {
        if (cipherOverride != null) {
            _primingState.value = VoiceKeyPrimingState.Ready
            return
        }
        if (_primingState.value is VoiceKeyPrimingState.Ready) return
        keyMutex.withLock {
            if (_primingState.value is VoiceKeyPrimingState.Ready) return
            try {
                val existing = secureStore.getString(PROFILE_KEY_SECURE)
                var mintedNewKey = false
                val key = when {
                    !existing.isNullOrBlank() -> Base64.decode(existing)
                    secureStore.contains(PROFILE_KEY_SECURE) ->
                        throw VoiceProfileKeyUnreadableException()
                    else -> {
                        mintedNewKey = true
                        val generated = ByteArray(PROFILE_KEY_BYTES).also {
                            Random.Default.nextBytes(it)
                        }
                        secureStore.putString(PROFILE_KEY_SECURE, Base64.encode(generated))
                        generated
                    }
                }
                // Purge before publishing the key so no reader observes mid-purge state.
                if (mintedNewKey && legacyKeyFile.exists() && !legacyPurgedMarker.exists()) {
                    withContext(Dispatchers.IO) {
                        purgeLegacyConstantKeyArtifacts()
                    }
                }
                primedKey = key
                _primingState.value = VoiceKeyPrimingState.Ready
            } catch (e: CancellationException) {
                primedKey = null
                if (_primingState.value !is VoiceKeyPrimingState.Failed) {
                    _primingState.value = VoiceKeyPrimingState.Unprimed
                }
                throw e
            } catch (e: Exception) {
                primedKey = null
                _primingState.value = VoiceKeyPrimingState.Failed(
                    errorClassName = e::class.simpleName ?: "Exception",
                )
                throw e
            }
        }
    }

    private suspend fun requireCipher(): ProfileCipher {
        cipherOverride?.let { return it }
        ensureKey()
        val key = primedKey
            ?: throw IllegalStateException("VoiceProfileStore key missing after ensureKey")
        return createProfileCipher(key)
    }

    /**
     * One-shot legacy migration when a brand-new SecureStore key was just minted.
     * Deletes only profiles that open under the bytes in `voices/.profile_key`
     * (provably legacy-key blobs). Always writes `.profile_key.purged` so a failed
     * delete of the legacy file cannot cause repeated purges. Never uses the old
     * constant key formula from production sources — only the on-disk file bytes.
     */
    private fun purgeLegacyConstantKeyArtifacts() {
        if (!legacyKeyFile.exists()) return
        if (legacyPurgedMarker.exists()) return
        val legacyBytes = runCatching { legacyKeyFile.readBytes() }.getOrNull()
        if (legacyBytes != null && legacyBytes.size == PROFILE_KEY_BYTES) {
            val legacyCipher = createProfileCipher(legacyBytes)
            if (profilesDir.exists()) {
                profilesDir.listChildren()
                    .filter { !it.isDirectory() && it.name.endsWith(".json") }
                    .forEach { metaFile ->
                        if (isProvablyLegacyKeyBlob(metaFile, legacyCipher)) {
                            delete(metaFile.name.removeSuffix(".json"))
                        }
                    }
            }
        }
        legacyKeyFile.deleteRecursively()
        // Record completion even when the legacy file delete fails, so remints
        // with a leftover `.profile_key` do not purge again.
        writeLegacyPurgedMarker()
    }

    /**
     * True only when meta parses, `.enc` exists, and the blob decrypts + unpacks under
     * [legacyCipher] with a matching dimension. Corrupt JSON, missing `.enc`, decrypt
     * failure, or dim mismatch → false (keep the files).
     */
    private fun isProvablyLegacyKeyBlob(
        metaFile: ModelStoreDir,
        legacyCipher: ProfileCipher,
    ): Boolean {
        val meta = runCatching {
            json.decodeFromString<VoiceProfileFile>(metaFile.readBytes().decodeToString())
        }.getOrNull() ?: return false
        val encFile = metaFile.resolveSiblingEnc()
        if (!encFile.exists()) return false
        return runCatching { meta.toProfile(encFile, legacyCipher) }.getOrNull() != null
    }

    private fun writeLegacyPurgedMarker() {
        root.resolve("voices").createDirectories()
        legacyPurgedMarker.writeBytes(MARKER_BYTES)
    }

    suspend fun listProfiles(): List<VoiceProfile> {
        val c = requireCipher()
        if (!profilesDir.exists()) return emptyList()
        return profilesDir.listChildren()
            .filter { !it.isDirectory() && it.name.endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    json.decodeFromString<VoiceProfileFile>(file.readBytes().decodeToString())
                        .toProfile(file.resolveSiblingEnc(), c)
                }.getOrNull()
            }
            .sortedBy { it.displayName.lowercase() }
    }

    suspend fun get(id: String): VoiceProfile? = listProfiles().firstOrNull { it.id == id }

    suspend fun upsert(
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

    suspend fun rename(id: String, displayName: String): VoiceProfile? {
        val existing = get(id) ?: return null
        val updated = existing.copy(displayName = displayName.trim().ifBlank { existing.displayName })
        writeProfile(updated)
        return updated
    }

    suspend fun updateMeta(
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

    /** File delete only — does not touch the cipher. */
    fun delete(id: String) {
        profilesDir.resolve("$id.json").deleteRecursively()
        profilesDir.resolve("$id.enc").deleteRecursively()
        // Legacy plaintext
        profilesDir.resolve("$id.f32").deleteRecursively()
    }

    /** File delete only — does not touch the cipher. Also clears legacy key artifacts. */
    fun wipeAll() {
        if (profilesDir.exists()) profilesDir.deleteRecursively()
        profilesDir.createDirectories()
        legacyKeyFile.deleteRecursively()
        legacyPurgedMarker.deleteRecursively()
    }

    suspend fun match(
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

    /**
     * Best-effort profile match for diarization: priming/cipher failures return null
     * (unlabeled speaker) instead of crashing the pipeline. Cancellation is rethrown.
     */
    suspend fun matchOrNull(
        embedding: FloatArray,
        threshold: Float = DEFAULT_MATCH_THRESHOLD,
    ): VoiceMatch? =
        try {
            match(embedding, threshold)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /**
     * Best-effort cluster-cache write for diarization. Failures are swallowed so ASR
     * can continue without a named speaker; cancellation is rethrown.
     */
    suspend fun cacheClusterEmbeddingOrSkip(
        recordingId: String,
        speakerId: String,
        embedding: FloatArray,
    ) {
        try {
            cacheClusterEmbedding(recordingId, speakerId, embedding)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Degrade: keep diarization spans without a cached embedding.
        }
    }

    /** Confirm a match — averages the new sample into the centroid. */
    suspend fun refine(id: String, embedding: FloatArray): VoiceProfile? {
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

    suspend fun merge(intoId: String, fromId: String): VoiceProfile? {
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

    suspend fun cacheClusterEmbedding(recordingId: String, speakerId: String, embedding: FloatArray) {
        val dir = cacheDir.resolve(recordingId)
        dir.createDirectories()
        val enc = requireCipher().encrypt(floatsToBytes(embedding))
        dir.resolve("${slug(speakerId)}.enc").writeBytes(enc)
        // Keep legacy plaintext cache for one release of promoteCached compatibility.
        dir.resolve("${slug(speakerId)}.f32").writeBytes(floatsToBytes(embedding))
    }

    suspend fun cachedClusterEmbedding(recordingId: String, speakerId: String): FloatArray? {
        val encFile = cacheDir.resolve(recordingId).resolve("${slug(speakerId)}.enc")
        if (encFile.exists() && encFile.length() > 0L) {
            return runCatching {
                bytesToFloats(requireCipher().decrypt(encFile.readBytes()))
            }.getOrNull()
        }
        val file = cacheDir.resolve(recordingId).resolve("${slug(speakerId)}.f32")
        if (!file.exists() || file.length() == 0L) return null
        return bytesToFloats(file.readBytes())
    }

    suspend fun promoteCached(
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
    suspend fun exportEncrypted(): String {
        val c = requireCipher()
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

    suspend fun importEncrypted(text: String): Int {
        val bundle = json.decodeFromString<ExportedBundle>(text)
        val c = requireCipher()
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

    private suspend fun writeProfile(profile: VoiceProfile) {
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
        enc.writeBytes(requireCipher().encrypt(packed))
        // Remove legacy plaintext if present.
        profilesDir.resolve("${profile.id}.f32").deleteRecursively()
    }

    companion object {
        const val DEFAULT_MATCH_THRESHOLD = 0.55f
        const val MAX_SAMPLES = 8
        const val PROFILE_KEY_SECURE = "voice.profile_aes_key_b64"
        const val PROFILE_KEY_BYTES = 32
        private val MARKER_BYTES = byteArrayOf(1)

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

/** Observed by settings UI for Retry when Keystore priming fails. */
sealed interface VoiceKeyPrimingState {
    data object Unprimed : VoiceKeyPrimingState
    data object Ready : VoiceKeyPrimingState
    data class Failed(val errorClassName: String) : VoiceKeyPrimingState
}

/**
 * SecureStore reports the voice-profile key slot as present ([SecureStore.contains]) but
 * [SecureStore.getString] could not return it. Priming must fail closed — never mint a
 * replacement key or purge profiles.
 */
class VoiceProfileKeyUnreadableException :
    IllegalStateException(
        "Voice profile key is present in SecureStore but could not be read",
    )

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
