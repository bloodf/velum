package dev.velum.ai.local

import kotlin.concurrent.Volatile
import dev.velum.core.security.SecureKeys
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Named voice profiles with encrypted embeddings, multi-sample centroids, and metadata.
 * Profiles never leave the device unless the user exports; [delete] / [wipeAll] remove blobs.
 * Production reads and writes use [withReady]. Synchronous operations require an already
 * primed secure key (or a local file key / explicit test cipher); they never block waiting for a key.
 */
@OptIn(ExperimentalEncodingApi::class)
class VoiceProfileStore(
    private val root: ModelStoreDir,
    private val secureStore: SecureStore? = null,
    private val cipherOverride: ProfileCipher? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        primedKey?.let { return it }
        // Legacy synchronous file-store callers remain supported. Production callers use
        // withReady, which also serializes file-key creation and moves disk I/O off the UI.
        if (secureStore == null) {
            check(keyInitializationMutex.tryLock()) { "Voice profiles are initializing. Use withReady." }
            try {
                return loadFileKey().also { publishKey(it) }
            } finally {
                keyInitializationMutex.unlock()
            }
        }
        error("Voice profiles are not ready. Use withReady before accessing profiles.")
    }

    @Volatile
    private var primedKey: ByteArray? = null
    private val accessMutex = Mutex()
    private val cipherIdentity = Any()
    private var keyRevision = Any()

    private fun publishKey(key: ByteArray) {
        if (primedKey?.contentEquals(key) != true) keyRevision = Any()
        primedKey = key
    }

    /** Await key readiness and perform profile I/O away from the UI thread. Failures propagate. */
    suspend fun <T> withReady(block: VoiceProfileStore.() -> T): T = withContext(ioDispatcher) {
        ensureKey() // fails fast without queueing behind a long operation
        accessMutex.withLock {
            // Again inside the lock: a wipe that ran while this call waited has forgotten the key (wipeVoiceData),
            // and the block must not see a store that is "not ready". Lock order: access, then key initialization.
            ensureKey()
            block()
        }
    }

    /** Idempotent across concurrent callers and stores sharing the same backing key. */
    suspend fun ensureKey() = primeKey(refreshExisting = false)

    /** Explicit retry rereads existing key material; it never replaces a missing previously primed key. */
    suspend fun reloadKey() = withContext(ioDispatcher) {
        accessMutex.withLock { primeKey(refreshExisting = true) }
    }

    private suspend fun primeKey(refreshExisting: Boolean) = withContext(ioDispatcher) {
        if (cipherOverride != null || (primedKey != null && !refreshExisting)) return@withContext
        keyInitializationMutex.withLock {
            if (primedKey != null && !refreshExisting) return@withLock
            val previouslyPrimed = primedKey != null
            val store = secureStore
            val key = if (store == null) {
                if (refreshExisting && previouslyPrimed && !root.resolve("voices/.profile_key").exists()) {
                    throw VoiceProfileKeyUnavailableException()
                }
                loadFileKey()
            } else {
                val encoded = store.getString(PROFILE_KEY_SECURE)
                if (encoded != null) {
                    val decoded = try {
                        Base64.decode(encoded)
                    } catch (_: IllegalArgumentException) {
                        throw VoiceProfileKeyUnavailableException()
                    }
                    if (decoded.size != 32) throw VoiceProfileKeyUnavailableException()
                    decoded
                } else {
                    if (refreshExisting && previouslyPrimed) throw VoiceProfileKeyUnavailableException()
                    checkNoEncryptedData()
                    generateProfileKey().also {
                        val encodedKey = Base64.encode(it)
                        store.putString(PROFILE_KEY_SECURE, encodedKey)
                        check(store.getString(PROFILE_KEY_SECURE) == encodedKey) {
                            "Voice profile key could not be saved; no profile data was written."
                        }
                    }
                }
            }
            // Publish only after the backing store has acknowledged the write. On failure or
            // cancellation the next caller retries the read, never substitutes another key.
            publishKey(key)
        }
    }

    private fun loadFileKey(): ByteArray {
        val keyFile = root.resolve("voices").resolve(".profile_key")
        if (keyFile.exists()) {
            return keyFile.readBytes().also {
                if (it.size != 32) throw VoiceProfileKeyUnavailableException()
            }
        }
        checkNoEncryptedData()
        root.resolve("voices").createDirectories()
        return generateProfileKey().also { keyFile.writeBytes(it) }
    }

    private fun checkNoEncryptedData() {
        fun hasEncryptedData(dir: ModelStoreDir): Boolean = dir.exists() && dir.listChildren().any {
            if (it.isDirectory()) hasEncryptedData(it) else it.name.endsWith(".enc")
        }
        if (hasEncryptedData(root.resolve("voices"))) throw VoiceProfileKeyUnavailableException()
    }

    /** Read-only inspection. Provider/key failures propagate rather than become file issues. */
    fun inspectProfiles(): VoiceProfileInventory {
        if (!profilesDir.exists()) return VoiceProfileInventory(emptyList(), emptyList())
        val profileCipher = cipher()
        val groups = profilesDir.listChildren()
            .filter { file -> PROFILE_SUFFIXES.any { file.name.endsWith(it) } }
            .groupBy { it.name.substringBeforeLast('.') }
        val readable = mutableListOf<VoiceProfile>()
        val unreadable = mutableListOf<UnreadableVoiceProfile>()
        var authenticated = 0
        var authenticationFailures = 0
        for ((id, files) in groups) {
            val byName = files.associateBy { it.name }
            // Diagnose exactly the bytes represented by a future removal confirmation.
            val snapshot = captureFiles(files)
            var label = id
            fun issue(reason: VoiceProfileReadProblem) {
                unreadable += UnreadableVoiceProfile(
                    id, label, reason, profilesDir.absolutePath, if (safeProfileId(id)) snapshot else null,
                    if (reason == VoiceProfileReadProblem.CannotDecrypt) cipherIdentity else null,
                    if (reason == VoiceProfileReadProblem.CannotDecrypt) keyRevision else null,
                )
            }
            val metaFile = byName["$id.json"]
            if (metaFile == null) { issue(VoiceProfileReadProblem.MissingMetadata); continue }
            val metadataBytes = snapshot?.get(metaFile.name)
            if (metadataBytes == null) { issue(VoiceProfileReadProblem.FileUnavailable); continue }
            val metadata = try {
                json.decodeFromString<VoiceProfileFile>(metadataBytes.decodeToString())
            } catch (_: SerializationException) {
                issue(VoiceProfileReadProblem.InvalidMetadata); continue
            }
            label = metadata.displayName.trim().ifEmpty { id }
            if (!safeProfileId(id) || metadata.id != id || metadata.dim <= 0) {
                issue(VoiceProfileReadProblem.InvalidMetadata); continue
            }
            val encrypted = byName["$id.enc"]
            val embeddingFile = encrypted ?: byName["$id.f32"]
            if (embeddingFile == null) { issue(VoiceProfileReadProblem.MissingEmbedding); continue }
            val bytes = snapshot?.get(embeddingFile.name)
            if (bytes == null) { issue(VoiceProfileReadProblem.FileUnavailable); continue }
            // Only an explicit wire/authentication exception is a per-file candidate.
            // Unknown/provider errors and cancellation abort the entire inspection.
            val plain = if (encrypted != null) {
                try {
                    profileCipher.decrypt(bytes).also { authenticated++ }
                } catch (_: ProfileCiphertextUnreadableException) {
                    authenticationFailures++
                    issue(VoiceProfileReadProblem.CannotDecrypt); continue
                }
            } else bytes
            val samples = try {
                if (encrypted != null) unpackSamples(plain) else listOf(bytesToFloats(plain))
            } catch (_: IllegalArgumentException) {
                issue(VoiceProfileReadProblem.InvalidEmbedding); continue
            } catch (_: IndexOutOfBoundsException) {
                issue(VoiceProfileReadProblem.InvalidEmbedding); continue
            }
            if (samples.isEmpty() || samples.any { it.size != metadata.dim || it.any { value -> !value.isFinite() } }) {
                issue(VoiceProfileReadProblem.InvalidEmbedding); continue
            }
            readable += VoiceProfile(
                id = metadata.id, displayName = metadata.displayName,
                embedding = averageEmbeddings(samples), sampleEmbeddings = samples,
                role = metadata.role, company = metadata.company, email = metadata.email,
                notes = metadata.notes, contactUri = metadata.contactUri, sampleCount = samples.size,
            )
        }
        // A wrong but well-formed key and a damaged blob are indistinguishable when
        // nothing authenticates. Never label all existing encrypted profiles corrupt.
        // The key check file tells them apart: if it authenticates, the key is right and the files that did not
        // are damaged individually, so they are listed as unreadable instead of blocking every profile.
        if (authenticationFailures > 0 && authenticated == 0 && !keyCheckAuthenticates(profileCipher)) {
            throw VoiceProfileKeyUnavailableException()
        }
        return VoiceProfileInventory(
            readable.sortedBy { it.displayName.lowercase() },
            unreadable.sortedBy { it.displayName.lowercase() },
        )
    }

    private fun keyCheckFile() = root.resolve("voices").resolve(KEY_CHECK_FILE)

    private fun keyCheckAuthenticates(c: ProfileCipher): Boolean = try {
        val file = keyCheckFile()
        file.exists() && c.decrypt(file.readBytes()).contentEquals(KEY_CHECK_PLAINTEXT)
    } catch (_: ProfileCiphertextUnreadableException) {
        false
    }

    /** Written with the next profile write (inspection stays read-only), so later damage can be told apart from a wrong key. */
    private fun writeKeyCheckIfMissing(c: ProfileCipher) {
        if (keyCheckFile().exists()) return
        runCatching { writeAtomically(root.resolve("voices"), KEY_CHECK_FILE, c.encrypt(KEY_CHECK_PLAINTEXT)) }
    }

    /** Strict compatibility API: exports/old callers must not silently omit unreadable data. */
    fun listProfiles(): List<VoiceProfile> = inspectProfiles().let {
        if (it.unreadable.isNotEmpty()) throw UnreadableVoiceProfileException()
        it.profiles
    }

    fun get(id: String): VoiceProfile? {
        require(safeProfileId(id)) { "Invalid voice profile identifier." }
        val inventory = inspectProfiles()
        if (inventory.unreadable.any { it.id == id }) throw UnreadableVoiceProfileException()
        return inventory.profiles.firstOrNull { it.id == id }
    }

    /** Explicit UI-confirmed removal; false means the files/readability changed since inspection. */
    suspend fun removeUnreadableProfile(profile: UnreadableVoiceProfile): Boolean = withContext(ioDispatcher) {
        if (profile.problem == VoiceProfileReadProblem.CannotDecrypt) ensureKey()
        accessMutex.withLock {
            if (profile.problem == VoiceProfileReadProblem.CannotDecrypt) ensureKey() // see withReady
            require(safeProfileId(profile.id)) { "Invalid voice profile identifier." }
            if (profile.owner != profilesDir.absolutePath || profile.files == null) return@withLock false
            val current = profileFiles(profile.id)
            val bytes = captureFiles(current) ?: return@withLock false
            if (!sameFiles(profile.files, bytes)) return@withLock false
            if (profile.problem == VoiceProfileReadProblem.CannotDecrypt) {
                if (profile.cipherIdentity !== cipherIdentity || profile.keyRevision !== keyRevision) return@withLock false
                primeKey(refreshExisting = true)
                if (profile.keyRevision !== keyRevision) return@withLock false
                val stillUnreadable = inspectProfiles().unreadable.any {
                    it.id == profile.id && it.problem == VoiceProfileReadProblem.CannotDecrypt
                }
                if (!stillUnreadable) return@withLock false
            }
            currentCoroutineContext().ensureActive()
            current.forEach { check(it.deleteFile()) { "Unable to remove requested voice profile file." } }
            check(current.none { it.exists() }) { "Unable to remove all requested voice profile files." }
            deleteTemporaryFiles(profile.id)
            true
        }
    }

    private fun readProfileFile(file: ModelStoreDir): ByteArray? = try {
        if (file.isDirectory()) null else file.readBytes()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun captureFiles(files: List<ModelStoreDir>): Map<String, ByteArray>? {
        val result = mutableMapOf<String, ByteArray>()
        for (file in files) result[file.name] = readProfileFile(file) ?: return null
        return result
    }

    private fun profileFiles(id: String): List<ModelStoreDir> = PROFILE_SUFFIXES.map {
        profilesDir.resolve(id + it)
    }.filter { it.exists() }

    private fun sameFiles(a: Map<String, ByteArray>, b: Map<String, ByteArray>): Boolean =
        a.keys == b.keys && a.all { (name, bytes) -> bytes.contentEquals(b.getValue(name)) }


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
        require(safeProfileId(id)) { "Invalid voice profile identifier." }
        profilesDir.resolve("$id.json").deleteRecursively()
        profilesDir.resolve("$id.enc").deleteRecursively()
        // Legacy plaintext
        profilesDir.resolve("$id.f32").deleteRecursively()
        deleteTemporaryFiles(id)
    }

    /** `<id>.json.tmp` holds plaintext name, email and notes; `.enc.tmp` / `.f32.tmp` hold embeddings. */
    private fun deleteTemporaryFiles(id: String) {
        PROFILE_SUFFIXES.forEach { profilesDir.resolve("$id$it.tmp").deleteRecursively() }
    }

    /**
     * Deletes every voice profile and cached embedding without needing the key, so it works when the key is
     * lost for good or the only profile is corrupt (the recovery path of the voice profiles screen).
     */
    suspend fun wipeVoiceData() = withContext(ioDispatcher) {
        accessMutex.withLock {
            wipeAll()
            // Forget the in-memory key (not the stored one): a key that vanished mid-session must not stay in use
            // for new profiles only memory has, or the next start finds encrypted files and no key. Same lock order
            // as reloadKey: access, then key initialization.
            keyInitializationMutex.withLock {
                primedKey = null
                keyRevision = Any()
            }
        }
    }

    /** False if voice data remains. */
    fun wipeAll(): Boolean {
        if (profilesDir.exists()) profilesDir.deleteRecursively()
        keyCheckFile().deleteRecursively()
        // Cluster embeddings from processed recordings are voice data too.
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        val gone = !profilesDir.exists() && !cacheDir.exists()
        profilesDir.createDirectories()
        return gone
    }

    fun match(
        embedding: FloatArray,
        threshold: Float = DEFAULT_MATCH_THRESHOLD,
    ): VoiceMatch? {
        var best: VoiceMatch? = null
        // Matching remains useful with a damaged sibling; inspection exposes every omitted item.
        for (profile in inspectProfiles().profiles) {
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
        val enc = cipher().encrypt(floatsToBytes(embedding))
        val dir = cacheDir.resolve(recordingId)
        dir.createDirectories()
        writeAtomically(dir, "${slug(speakerId)}.enc", enc)
        // Encrypted only (#320); an older plaintext copy of this speaker goes too.
        dir.resolve("${slug(speakerId)}.f32").deleteFile()
    }

    /**
     * The cached speakers of [recordingId] after a diarization run: an older run's speakers go first, so a stale
     * "S1" can't be enrolled as someone else's voice when this run numbered its speakers differently.
     */
    fun replaceClusterEmbeddings(recordingId: String, embeddingsBySpeaker: Map<String, FloatArray>) {
        deleteClusterCache(recordingId)
        embeddingsBySpeaker.forEach { (speakerId, embedding) -> cacheClusterEmbedding(recordingId, speakerId, embedding) }
    }

    /** Deletes plaintext `.f32` embeddings earlier versions wrote next to the encrypted ones (#320). No key needed. */
    fun purgePlaintextClusterCache() {
        cacheDir.walkFiles().filter { it.name.endsWith(".f32") }.forEach { it.deleteFile() }
    }

    /** Recordings with cached speaker embeddings, so startup can drop those whose recording is gone (#320). */
    fun cachedRecordingIds(): Set<String> =
        if (cacheDir.isDirectory()) cacheDir.listChildren().filter { it.isDirectory() }.map { it.name }.toSet() else emptySet()

    /** The speaker embeddings of [recordingId], for when the recording is permanently deleted. */
    fun deleteClusterCache(recordingId: String) {
        require(safeProfileId(recordingId)) { "Invalid recording identifier." }
        cacheDir.resolve(recordingId).deleteRecursively()
    }

    fun cachedClusterEmbedding(recordingId: String, speakerId: String): FloatArray? {
        val encFile = cacheDir.resolve(recordingId).resolve("${slug(speakerId)}.enc")
        if (encFile.exists()) {
            // A truncated or foreign file (crash mid-write, other key) is a miss: the cache is only a convenience.
            return try {
                bytesToFloats(cipher().decrypt(encFile.readBytes()))
            } catch (_: ProfileCiphertextUnreadableException) {
                null
            }
        }
        // Plaintext copies from earlier versions are never read (#320).
        return null
    }

    /** Names and availability of one diarization run's speaker naming. */
    class SpeakerNaming(val names: Map<String, String>, val unavailable: Boolean) {
        /** The result a diarizer returns for [spans], carrying the naming outcome (null: no profile store). */
        companion object {
            fun resultFor(
                spans: List<dev.velum.ai.engine.SpeakerSpan>,
                naming: SpeakerNaming?,
                engineId: String,
                modelId: String,
                embeddings: Map<String, FloatArray> = emptyMap(),
            ) = dev.velum.ai.engine.DiarizationResult(
                spans = spans,
                namedSpeakers = naming?.names.orEmpty(),
                engineId = engineId,
                modelId = modelId,
                speakerNamesUnavailable = naming?.unavailable == true,
                embeddings = embeddings,
            )
        }
    }

    /**
     * Names the speakers of one diarization run that match a saved profile, without touching the speaker cache. The
     * cache is written by [cacheSpeakerEmbeddings] together with the speaker rows. When the key is missing or the
     * secure store is unavailable nothing is named and [SpeakerNaming.unavailable] is set.
     */
    suspend fun nameSpeakers(embeddingsBySpeaker: Map<String, FloatArray>, matchThreshold: Float): SpeakerNaming = try {
        SpeakerNaming(
            withReady {
                buildMap {
                    for ((speakerId, embedding) in embeddingsBySpeaker) {
                        match(embedding, threshold = matchThreshold)?.let { put(speakerId, it.profile.displayName) }
                    }
                }
            },
            unavailable = false,
        )
    } catch (e: VoiceProfileKeyUnavailableException) {
        println("VelumVoice: speaker naming unavailable (${e::class.simpleName})")
        SpeakerNaming(emptyMap(), unavailable = true)
    } catch (e: SecureStoreUnavailableException) {
        println("VelumVoice: speaker naming unavailable (${e::class.simpleName})")
        SpeakerNaming(emptyMap(), unavailable = true)
    }

    /**
     * Drops the recording's cached speaker embeddings, under the store's access lock and off the caller's thread.
     * Called before a run's speaker numbering is written, so the old numbering never outlives the labels it described.
     * The delete is verified: a directory that is still there (a file the platform would not remove) throws
     * [SpeakerCacheNotDroppedException], so the caller stops before it changes anything the cache must match.
     */
    suspend fun dropSpeakerCache(recordingId: String) = withContext(ioDispatcher) {
        accessMutex.withLock {
            deleteClusterCache(recordingId)
            if (cacheDir.resolve(recordingId).exists()) throw SpeakerCacheNotDroppedException()
        }
    }

    /**
     * Replaces the recording's cached speakers with this run's, to be called right after its speaker rows were
     * written. When the cache cannot be written (no key) the old one is dropped instead: it belongs to the previous
     * run's numbering and must not outlive the rows it described.
     */
    suspend fun cacheSpeakerEmbeddings(recordingId: String, embeddingsBySpeaker: Map<String, FloatArray>) {
        try {
            withReady { replaceClusterEmbeddings(recordingId, embeddingsBySpeaker) }
        } catch (e: VoiceProfileKeyUnavailableException) {
            dropCacheAfterUnavailable(recordingId, e)
        } catch (e: SecureStoreUnavailableException) {
            dropCacheAfterUnavailable(recordingId, e)
        }
    }

    /**
     * The old cache must not outlive a run that could not replace it: once the key is back, naming "S1" would
     * read the previous run's "S1", which may be a different person.
     */
    private suspend fun dropCacheAfterUnavailable(recordingId: String, cause: Exception) {
        println("VelumVoice: speaker cache unavailable (${cause::class.simpleName})")
        withContext(NonCancellable + ioDispatcher) {
            accessMutex.withLock {
                runCatching { deleteClusterCache(recordingId) }
                    .onFailure { println("VelumVoice: dropping the old speaker cache failed (${it::class.simpleName})") }
            }
        }
    }

    /**
     * Whether speaker naming can run now: the key is there and the saved profiles authenticate or are only
     * individually damaged. Checked before a long transcription so a rerun that would have to be thrown away
     * fails early.
     */
    suspend fun speakerNamingReady(): Boolean = try {
        withReady { inspectProfiles() }
        true
    } catch (_: VoiceProfileKeyUnavailableException) {
        false
    } catch (_: SecureStoreUnavailableException) {
        false
    }

    /**
     * Re-encrypts profiles still stored as plaintext `.f32` by earlier versions and removes the plaintext. Needs
     * the key only when such a file exists, so a fresh install creates no key here. A profile whose `.enc` already
     * exists and authenticates only has its leftover plaintext removed (a crash between the two steps).
     *
     * A profile whose `.enc` does not authenticate but whose `.f32` is intact (a truncated `.enc` left by the old
     * non-atomic write) is rewritten from the `.f32`, then the plaintext is removed, but only when the key check
     * file authenticates: that proves the current key is right, so the bad `.enc` is damage and not a file made
     * under another key. Without that proof (no key check yet, or it fails) neither file is touched.
     *
     * Returns how many profiles lost their plaintext copy.
     */
    suspend fun migrateLegacyPlaintextProfiles(): Int {
        if (!profilesDir.exists() || profilesDir.listChildren().none { it.name.endsWith(".f32") }) return 0
        return withReady {
            val plaintextIds = profilesDir.listChildren().filter { it.name.endsWith(".f32") }
                .map { it.name.removeSuffix(".f32") }.toSet()
            val inventory = inspectProfiles()
            val readable = inventory.profiles.filter { it.id in plaintextIds }
            val damaged = inventory.unreadable.filter {
                it.id in plaintextIds && it.problem == VoiceProfileReadProblem.CannotDecrypt
            }
            // The proof is taken before this call writes anything: migrating a lone plaintext profile writes the
            // key check itself, which must not count as proof for the damaged files found in the same call.
            val keyProven = damaged.isNotEmpty() && keyCheckAuthenticates(cipher())
            val migrated = readable.count { profile ->
                if (profilesDir.resolve("${profile.id}.enc").exists()) {
                    profilesDir.resolve("${profile.id}.f32").deleteFile()
                } else {
                    writeProfile(profile) // durable .enc first, then it removes the .f32
                    !profilesDir.resolve("${profile.id}.f32").exists()
                }
            }
            if (!keyProven) return@withReady migrated
            migrated + damaged.count { issue -> rewriteFromPlaintext(issue.id) }
        }
    }

    /** Re-encrypts [id] from its intact `.f32` over a damaged `.enc`; false (nothing touched) if the `.f32` is not intact. */
    private fun rewriteFromPlaintext(id: String): Boolean {
        if (!safeProfileId(id)) return false
        val metadata = try {
            json.decodeFromString<VoiceProfileFile>(profilesDir.resolve("$id.json").readBytes().decodeToString())
        } catch (_: Exception) {
            return false
        }
        val embedding = try {
            bytesToFloats(profilesDir.resolve("$id.f32").readBytes())
        } catch (_: Exception) {
            return false
        }
        if (metadata.id != id || metadata.dim <= 0 || embedding.size != metadata.dim || embedding.any { !it.isFinite() }) return false
        // An all-zero vector is what a blank or wiped file reads as; it would replace the .enc with a profile that never matches.
        if (embedding.all { it == 0f }) return false
        writeProfile(
            VoiceProfile(
                id = id, displayName = metadata.displayName, embedding = embedding, sampleEmbeddings = listOf(embedding),
                role = metadata.role, company = metadata.company, email = metadata.email, notes = metadata.notes,
                contactUri = metadata.contactUri, sampleCount = 1,
            ),
        )
        return !profilesDir.resolve("$id.f32").exists()
    }

    /** Leftover `*.tmp` files of an interrupted write. A `.json.tmp` holds plaintext name, email and notes. */
    suspend fun sweepTemporaryFiles() = withContext(ioDispatcher) {
        accessMutex.withLock {
            (profilesDir.walkFiles() + cacheDir.walkFiles()).filter { it.name.endsWith(".tmp") }.forEach { it.deleteFile() }
        }
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
        val bundle = try {
            json.decodeFromString<ExportedBundle>(text)
        } catch (e: SerializationException) {
            throw InvalidVoiceProfileBackupException(e)
        } catch (e: IllegalArgumentException) {
            throw InvalidVoiceProfileBackupException(e)
        }
        val c = cipher()
        // Decode every entry before writing any, so a bad entry never leaves a partial import behind.
        val decoded = bundle.profiles.map { e ->
            val embedding: FloatArray
            val samples: List<FloatArray>
            try {
                embedding = bytesToFloats(c.decrypt(Base64.decode(e.embeddingB64)))
                samples = e.samplesB64.map { bytesToFloats(c.decrypt(Base64.decode(it))) }
                    .ifEmpty { listOf(embedding) }
            } catch (bad: ProfileCiphertextUnreadableException) {
                throw bad // wrong key or damaged data: reported separately from a malformed bundle
            } catch (bad: IllegalArgumentException) {
                throw InvalidVoiceProfileBackupException(bad)
            }
            val id = e.id.ifBlank { slug(e.displayName) }
            if (!safeProfileId(id)) {
                throw InvalidVoiceProfileBackupException(IllegalArgumentException("Invalid voice profile identifier."))
            }
            get(id) // Refuse to overwrite unreadable files; recovery requires an explicit removal.
            VoiceProfile(
                id = id,
                displayName = e.displayName,
                embedding = embedding,
                sampleEmbeddings = samples,
                role = e.role,
                company = e.company,
                email = e.email,
                notes = e.notes,
                contactUri = e.contactUri,
                sampleCount = samples.size,
            )
        }
        decoded.forEach { writeProfile(it) }
        return decoded.size
    }

    private fun writeProfile(profile: VoiceProfile) {
        require(safeProfileId(profile.id)) { "Invalid voice profile identifier." }
        val packed = packSamples(profile.sampleEmbeddings.ifEmpty { listOf(profile.embedding) })
        val encrypted = cipher().encrypt(packed)
        profilesDir.createDirectories()
        writeAtomically(profilesDir, "${profile.id}.json",
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
        writeAtomically(profilesDir, "${profile.id}.enc", encrypted)
        writeKeyCheckIfMissing(cipher())
        // Remove legacy plaintext if present.
        profilesDir.resolve("${profile.id}.f32").deleteRecursively()
    }

    /** Temp file then rename, so a crash leaves the old file or the new one, never a truncated one. */
    private fun writeAtomically(dir: ModelStoreDir, fileName: String, bytes: ByteArray) {
        val tmp = dir.resolve("$fileName.tmp")
        try {
            tmp.writeBytesDurably(bytes)
            // The file name is a slug of a person's name: keep it out of the message.
            check(tmp.renameTo(dir.resolve(fileName))) { "Unable to publish a voice file." }
            dir.syncDirectory()
        } finally {
            if (tmp.exists()) tmp.deleteFile()
        }
    }

    companion object {
        private const val KEY_CHECK_FILE = ".keycheck.enc"
        private val KEY_CHECK_PLAINTEXT = "velum-voice-key-check-v1".encodeToByteArray()
        // Shared across instances: two cold-start containers must not overwrite one another's key.
        private val keyInitializationMutex = Mutex()
        private val PROFILE_SUFFIXES = listOf(".json", ".enc", ".f32")
        private fun safeProfileId(id: String): Boolean =
            id.isNotBlank() && id != "." && id != ".." && '/' !in id && '\\' !in id
        const val DEFAULT_MATCH_THRESHOLD = 0.55f
        const val MAX_SAMPLES = 8
        const val PROFILE_KEY_SECURE = SecureKeys.VOICE_PROFILE_AES_KEY

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
            val expected = 8L + count.toLong() * dim * 4L
            if (bytes.size.toLong() < expected) return listOf(bytesToFloats(bytes))
            return (0 until count).map { i ->
                val start = 8 + i * dim * 4
                bytesToFloats(bytes.copyOfRange(start, start + dim * 4))
            }
        }
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


data class VoiceProfileInventory(
    val profiles: List<VoiceProfile>,
    val unreadable: List<UnreadableVoiceProfile>,
)

enum class VoiceProfileReadProblem {
    MissingMetadata, MissingEmbedding, InvalidMetadata, InvalidEmbedding, CannotDecrypt, FileUnavailable,
}

class UnreadableVoiceProfile internal constructor(
    val id: String,
    val displayName: String,
    val problem: VoiceProfileReadProblem,
    internal val owner: String,
    internal val files: Map<String, ByteArray>?,
    internal val cipherIdentity: Any?,
    internal val keyRevision: Any?,
) {
    val canRemove: Boolean get() = files != null
}

/** Saved as the processing error when the previous speaker cache cannot be removed; the UI matches this exact text to translate it (#348). */
const val SPEAKER_CACHE_NOT_DROPPED_MESSAGE =
    "The saved speaker voices could not be cleared, so the recording was left unchanged. Try again."

/** The previous run's speaker cache could not be removed, so the recording was left as it was. */
class SpeakerCacheNotDroppedException : IllegalStateException(SPEAKER_CACHE_NOT_DROPPED_MESSAGE)

/** The pasted text is not a Voice Profiles backup (not JSON, wrong shape, or bad base64). */
class InvalidVoiceProfileBackupException(cause: Throwable) : IllegalArgumentException(
    "The text is not a voice profile backup.", cause,
)

class UnreadableVoiceProfileException : IllegalStateException(
    "Some voice profile files are unreadable. Inspect them before changing or exporting these profiles.",
)

class VoiceProfileKeyUnavailableException : IllegalStateException(
    "The voice profile key is unavailable or cannot authenticate the stored profiles. Files were preserved.",
)
