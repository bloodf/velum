package dev.velum.recorder.plaud

import dev.velum.protocol.DeviceAudioFormat
import dev.velum.protocol.DeviceCapabilityRegistry
import dev.velum.protocol.PlaudUuids
import dev.velum.recorder.RecorderAudioCodec
import dev.velum.recorder.RecorderCapabilities
import dev.velum.recorder.RecorderDriver
import dev.velum.recorder.RecorderProtocol

object PlaudRecorderDriver : RecorderDriver {
    override val id: String = "plaud"
    override val displayName: String = "Plaud"
    override val protocolLicenseNote: String =
        "Community reverse-engineering (MIT research notes); firmware proprietary"

    override val protocol: RecorderProtocol = object : RecorderProtocol {
        override fun describe(): String =
            "Plaud GATT ${PlaudUuids.SERVICE} / notify ${PlaudUuids.NOTIFY} / write ${PlaudUuids.WRITE}"
    }

    override fun matchesAdvertisement(
        name: String?,
        serviceUuids: List<String>,
        manufacturerId: Int?,
        manufacturerPayload: ByteArray?,
    ): Boolean {
        val services = serviceUuids.map { it.lowercase().replace("-", "") }
        if (services.any { it.contains("1910") || it == PlaudUuids.SERVICE.lowercase().replace("-", "") }) {
            return true
        }
        if (manufacturerId == PlaudUuids.MANUFACTURER_ID) return true
        val n = name?.lowercase().orEmpty()
        return n.contains("plaud") || n.contains("notepin") || n.contains("note pro") ||
            n.startsWith("note ") || Regex("""^note\d""", RegexOption.IGNORE_CASE).containsMatchIn(n)
    }

    override fun capabilitiesForAdvertisement(
        name: String?,
        manufacturerPayload: ByteArray?,
    ): RecorderCapabilities {
        val productCode = manufacturerPayload?.let { payload ->
            if (payload.size >= 2) {
                (payload[0].toInt() and 0xff) or ((payload[1].toInt() and 0xff) shl 8)
            } else null
        }
        val caps = DeviceCapabilityRegistry.detect(
            productCode = productCode,
            advertisedName = name,
        )
        return RecorderCapabilities(
            driverId = id,
            productLabel = caps.displayName,
            supportsFileListDownload = true,
            supportsLiveAudioStream = false,
            supportsWifiFastPath = DeviceCapabilityRegistry.supportsWifiFastPath(caps),
            audioCodecs = caps.audioFormats.map { it.toRecorderCodec() }.toSet(),
            needsImportedIdentityKeys = true,
            notes = caps.notes,
        )
    }
}

private fun DeviceAudioFormat.toRecorderCodec(): RecorderAudioCodec = when (this) {
    DeviceAudioFormat.OggOpusInPlaudContainer -> RecorderAudioCodec.OggOpusInContainer
    DeviceAudioFormat.RawOpusSegments -> RecorderAudioCodec.RawOpus
    DeviceAudioFormat.Adpcm -> RecorderAudioCodec.Adpcm
    DeviceAudioFormat.PcmWav -> RecorderAudioCodec.Pcm16
}
