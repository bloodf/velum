package dev.velum.recorder.pocket

import dev.velum.recorder.RecorderAudioCodec
import dev.velum.recorder.RecorderCapabilities
import dev.velum.recorder.RecorderDriver
import dev.velum.recorder.RecorderProtocol

object PocketRecorderDriver : RecorderDriver {
    override val id: String = "pocket"
    override val displayName: String = "Pocket / OpenPocket"
    override val protocolLicenseNote: String = "MIT (pocket-libre)"

    override val protocol: RecorderProtocol = object : RecorderProtocol {
        override fun describe(): String =
            "Pocket ASCII ${PocketProtocol.CMD_PREFIX}/${PocketProtocol.RSP_PREFIX} on ${PocketUuids.AUDIO_SERVICE}"
    }

    override fun matchesAdvertisement(
        name: String?,
        serviceUuids: List<String>,
        manufacturerId: Int?,
        manufacturerPayload: ByteArray?,
    ): Boolean {
        val n = name?.uppercase().orEmpty()
        if (n.startsWith(PocketUuids.DEVICE_NAME_PREFIX)) return true
        val services = serviceUuids.map { it.lowercase().replace("-", "") }
        if (PocketUuids.ADVERTISED_SERVICES.any { adv ->
                services.any { it == adv.lowercase() || it.endsWith(adv.lowercase()) }
            }
        ) {
            return true
        }
        val audio = PocketUuids.AUDIO_SERVICE.lowercase().replace("-", "")
        return services.any { it == audio || it.contains("001120a0") }
    }

    override fun capabilitiesForAdvertisement(
        name: String?,
        manufacturerPayload: ByteArray?,
    ): RecorderCapabilities = RecorderCapabilities(
        driverId = id,
        productLabel = name?.takeIf { it.isNotBlank() } ?: "Pocket PKT01",
        supportsFileListDownload = true,
        supportsLiveAudioStream = true,
        supportsWifiFastPath = true,
        audioCodecs = setOf(RecorderAudioCodec.Mp3),
        needsImportedIdentityKeys = true,
        notes = "16-char session key (APP&SK&); MP3 16 kHz mono. OpenPocket DIY aims at the same protocol.",
    )
}
