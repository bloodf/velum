package dev.velum.recorder

import dev.velum.recorder.omi.OmiRecorderDriver
import dev.velum.recorder.plaud.PlaudRecorderDriver
import dev.velum.recorder.pocket.PocketRecorderDriver

/**
 * Ordered registry of pluggable recorder families. First match wins on scan.
 */
object RecorderDriverRegistry {
    val drivers: List<RecorderDriver> = listOf(
        PlaudRecorderDriver,
        OmiRecorderDriver,
        PocketRecorderDriver,
    )

    fun byId(id: String): RecorderDriver? =
        drivers.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun matchAdvertisement(
        name: String?,
        serviceUuids: List<String> = emptyList(),
        manufacturerId: Int? = null,
        manufacturerPayload: ByteArray? = null,
    ): RecorderScanMatch? {
        for (driver in drivers) {
            if (driver.matchesAdvertisement(name, serviceUuids, manufacturerId, manufacturerPayload)) {
                return RecorderScanMatch(
                    driver = driver,
                    capabilities = driver.capabilitiesForAdvertisement(name, manufacturerPayload),
                )
            }
        }
        return null
    }
}
