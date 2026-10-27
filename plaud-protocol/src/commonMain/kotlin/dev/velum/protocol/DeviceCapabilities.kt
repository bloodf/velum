package dev.velum.protocol

/**
 * On-device audio container / codec as shipped by Plaud recorders (from APK + protocol research).
 */
enum class DeviceAudioFormat {
    /** Note Pro / modern SoftAP path: PLAUD.AI header wrapping Ogg Opus. */
    OggOpusInPlaudContainer,
    /** Older Note sync: raw Opus frames without Ogg. */
    RawOpusSegments,
    /** ADPCM variants seen on some Pin / older firmwares. */
    Adpcm,
    /** Uncompressed PCM WAV (rare; some export / debug paths). */
    PcmWav,
}

/**
 * Static + handshake-refined capability profile for a Plaud product code.
 *
 * Forbidden opcodes (5, 104, 128, 0xFE20, 143) and forbidden settings (4, 26)
 * apply to **every** model — see [ForbiddenOpcodes] / [ForbiddenSettings].
 */
data class DeviceCapabilities(
    val model: DeviceModel,
    val displayName: String,
    /** BLE advertised name prefixes observed in the wild / APK. */
    val namePrefixes: List<String>,
    val defaultProtocolMin: Int,
    val defaultProtocolMax: Int,
    val wifiFastTransfer: Boolean,
    val softApWebSocket8081: Boolean,
    val audioFormats: Set<DeviceAudioFormat>,
    val sampleRatesHz: Set<Int>,
    val maxChannels: Int,
    val ledControl: Boolean,
    val bleNameWritable: Boolean,
    val buttonCustomize: Boolean,
    val notes: String = "",
) {
    val commandTable: CommandTable
        get() = CommandTable.forModel(model, defaultProtocolMin)

    fun withHandshake(hs: HandshakeResponse): DeviceCapabilities = copy(
        wifiFastTransfer = wifiFastTransfer && hs.wifiSupported,
        softApWebSocket8081 = softApWebSocket8081 && hs.wifiSupported,
        maxChannels = maxOf(1, hs.audioChannels).coerceAtMost(maxChannels),
        audioFormats = when {
            hs.oggAudio -> audioFormats + DeviceAudioFormat.OggOpusInPlaudContainer
            else -> audioFormats
        },
    )
}

/**
 * Registry of Plaud recorders Velum supports (same BLE protocol family).
 * Auto-detect via manufacturer-data product code, optional name-prefix fallback.
 */
object DeviceCapabilityRegistry {
    private val profiles: Map<DeviceModel, DeviceCapabilities> = listOf(
        DeviceCapabilities(
            model = DeviceModel.PlaudOne,
            displayName = "Plaud One",
            namePrefixes = listOf("Plaud One", "PLAUD ONE", "One"),
            defaultProtocolMin = 30,
            defaultProtocolMax = 39,
            wifiFastTransfer = true,
            softApWebSocket8081 = true,
            audioFormats = setOf(DeviceAudioFormat.OggOpusInPlaudContainer),
            sampleRatesHz = setOf(16_000),
            maxChannels = 2,
            ledControl = true,
            bleNameWritable = true,
            buttonCustomize = true,
            notes = "ECC unlock (prot ≥ 30).",
        ),
        DeviceCapabilities(
            model = DeviceModel.NotePin,
            displayName = "NotePin",
            namePrefixes = listOf("NotePin", "NOTEPIN", "Plaud NotePin"),
            defaultProtocolMin = 20,
            defaultProtocolMax = 29,
            wifiFastTransfer = true,
            softApWebSocket8081 = true,
            audioFormats = setOf(
                DeviceAudioFormat.OggOpusInPlaudContainer,
                DeviceAudioFormat.Adpcm,
            ),
            sampleRatesHz = setOf(16_000),
            maxChannels = 1,
            ledControl = true,
            bleNameWritable = true,
            buttonCustomize = true,
        ),
        DeviceCapabilities(
            model = DeviceModel.NotePinS,
            displayName = "NotePin S",
            namePrefixes = listOf("NotePin S", "NOTEPIN S", "NotePinS"),
            defaultProtocolMin = 20,
            defaultProtocolMax = 29,
            wifiFastTransfer = true,
            softApWebSocket8081 = true,
            audioFormats = setOf(
                DeviceAudioFormat.OggOpusInPlaudContainer,
                DeviceAudioFormat.Adpcm,
            ),
            sampleRatesHz = setOf(16_000),
            maxChannels = 1,
            ledControl = true,
            bleNameWritable = true,
            buttonCustomize = true,
        ),
        DeviceCapabilities(
            model = DeviceModel.NotePro,
            displayName = "Note Pro",
            namePrefixes = listOf("NotePro", "Note Pro", "NOTEPRO", "Plaud Note Pro"),
            defaultProtocolMin = 20,
            defaultProtocolMax = 29,
            wifiFastTransfer = true,
            softApWebSocket8081 = true,
            audioFormats = setOf(DeviceAudioFormat.OggOpusInPlaudContainer),
            sampleRatesHz = setOf(16_000),
            maxChannels = 2,
            ledControl = true,
            bleNameWritable = true,
            buttonCustomize = true,
            notes = "OTA push disabled in official app; Velum never sends 143.",
        ),
        DeviceCapabilities(
            model = DeviceModel.NotePro883,
            displayName = "Note Pro",
            namePrefixes = listOf("NotePro", "Note Pro", "NOTEPRO"),
            defaultProtocolMin = 20,
            defaultProtocolMax = 29,
            wifiFastTransfer = true,
            softApWebSocket8081 = true,
            audioFormats = setOf(DeviceAudioFormat.OggOpusInPlaudContainer),
            sampleRatesHz = setOf(16_000),
            maxChannels = 2,
            ledControl = true,
            bleNameWritable = true,
            buttonCustomize = true,
            notes = "Project 883 — same Note Pro family as 881.",
        ),
        DeviceCapabilities(
            model = DeviceModel.Note,
            displayName = "Note",
            namePrefixes = listOf("Plaud Note", "NOTE", "Note "),
            defaultProtocolMin = 10,
            defaultProtocolMax = 29,
            wifiFastTransfer = false,
            softApWebSocket8081 = false,
            audioFormats = setOf(
                DeviceAudioFormat.RawOpusSegments,
                DeviceAudioFormat.OggOpusInPlaudContainer,
            ),
            sampleRatesHz = setOf(16_000),
            maxChannels = 1,
            ledControl = true,
            bleNameWritable = true,
            buttonCustomize = false,
            notes = "Older LED/clock opcodes; Wi-Fi SoftAP not relied on.",
        ),
    ).associateBy { it.model }

    fun all(): Collection<DeviceCapabilities> = profiles.values

    fun forModel(model: DeviceModel): DeviceCapabilities =
        profiles[model] ?: unknownProfile(model)

    fun forProductCode(code: Int): DeviceCapabilities =
        forModel(DeviceModel.fromCode(code))

    /**
     * Prefer manufacturer-data product code; fall back to BLE advertised name prefixes
     * when the code is missing/unknown (newer SKUs that still speak the same protocol).
     */
    fun detect(
        productCode: Int?,
        advertisedName: String? = null,
        handshake: HandshakeResponse? = null,
    ): DeviceCapabilities {
        val fromCode = productCode?.let { DeviceModel.fromCode(it) }
            ?.takeUnless { it == DeviceModel.Unknown }
            ?.let { forModel(it) }

        val fromName = if (fromCode == null) detectByName(advertisedName) else null
        val base = fromCode ?: fromName ?: unknownProfile(
            productCode?.let { DeviceModel.fromCode(it) } ?: DeviceModel.Unknown,
            advertisedName,
        )
        return if (handshake != null) base.withHandshake(handshake) else base
    }

    fun detectByName(advertisedName: String?): DeviceCapabilities? {
        if (advertisedName.isNullOrBlank()) return null
        val n = advertisedName.trim()
        // Longer / more specific prefixes first.
        val ranked = profiles.values.sortedByDescending { caps ->
            caps.namePrefixes.maxOfOrNull { it.length } ?: 0
        }
        for (caps in ranked) {
            if (caps.namePrefixes.any { prefix ->
                    n.equals(prefix, ignoreCase = true) ||
                        n.startsWith(prefix, ignoreCase = true)
                }
            ) {
                return caps
            }
        }
        // Generic "Plaud" without a known SKU → Unknown (still protocol-gated).
        return null
    }

    fun supportsWifiFastPath(caps: DeviceCapabilities): Boolean =
        caps.wifiFastTransfer && caps.softApWebSocket8081

    private fun unknownProfile(
        model: DeviceModel,
        advertisedName: String? = null,
    ): DeviceCapabilities = DeviceCapabilities(
        model = model,
        displayName = advertisedName?.takeIf { it.isNotBlank() }
            ?: if (model == DeviceModel.Unknown) "Plaud (unknown)" else model.displayName,
        namePrefixes = emptyList(),
        defaultProtocolMin = 20,
        defaultProtocolMax = 39,
        // Conservative: allow SoftAP attempt only after handshake.wifiSupported refines it.
        wifiFastTransfer = true,
        softApWebSocket8081 = true,
        audioFormats = setOf(DeviceAudioFormat.OggOpusInPlaudContainer),
        sampleRatesHz = setOf(16_000),
        maxChannels = 2,
        ledControl = true,
        bleNameWritable = true,
        buttonCustomize = false,
        notes = "Unknown product code — treat as protocol-compatible; refine via handshake.",
    )
}
