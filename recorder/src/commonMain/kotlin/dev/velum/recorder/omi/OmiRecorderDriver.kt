package dev.velum.recorder.omi

import dev.velum.recorder.RecorderAudioCodec
import dev.velum.recorder.RecorderCapabilities
import dev.velum.recorder.RecorderDriver
import dev.velum.recorder.RecorderProtocol

object OmiRecorderDriver : RecorderDriver {
    override val id: String = "omi"
    override val displayName: String = "Omi / Friend"
    override val protocolLicenseNote: String = "MIT (BasedHardware/omi)"

    override val protocol: RecorderProtocol = object : RecorderProtocol {
        override fun describe(): String =
            "Omi audio service ${OmiUuids.SERVICE}; codecs ${OmiCodec.entries.joinToString { "${it.name}=${it.code}" }}"
    }

    override fun matchesAdvertisement(
        name: String?,
        serviceUuids: List<String>,
        manufacturerId: Int?,
        manufacturerPayload: ByteArray?,
    ): Boolean {
        val normalized = serviceUuids.map { it.lowercase().replace("-", "") }
        val omi = OmiUuids.SERVICE.lowercase().replace("-", "")
        if (normalized.any { it == omi || it.contains("19b10000") }) return true
        val n = name?.lowercase().orEmpty()
        return n.contains("omi") || n.contains("friend") || n.contains("openglass")
    }

    override fun capabilitiesForAdvertisement(
        name: String?,
        manufacturerPayload: ByteArray?,
    ): RecorderCapabilities {
        val label = when {
            name.isNullOrBlank() -> "Omi"
            name.contains("glass", ignoreCase = true) -> "OmiGlass / OpenGlass"
            name.contains("friend", ignoreCase = true) -> "Friend"
            else -> name
        }
        return RecorderCapabilities(
            driverId = id,
            productLabel = label,
            supportsFileListDownload = false,
            supportsLiveAudioStream = true,
            supportsWifiFastPath = false,
            audioCodecs = setOf(
                RecorderAudioCodec.RawOpus,
                RecorderAudioCodec.Pcm16,
                RecorderAudioCodec.MuLaw,
            ),
            needsImportedIdentityKeys = false,
            notes = "Live BLE Opus/PCM; DIY XIAO boards with Friend firmware match the same service UUID.",
        )
    }
}
