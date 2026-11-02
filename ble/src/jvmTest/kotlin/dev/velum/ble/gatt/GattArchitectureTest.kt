package dev.velum.ble.gatt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Choke-point check (#19): only :ble (and the :ble-testing fakes module, once it exists) may touch
 * raw GATT writes. Drivers/app code receive BleTransport or GuardedLink and nothing else.
 */
class GattArchitectureTest {
    private val banned = listOf("writeRaw", "SealedWrite(", "PlainWrite(", "GattWrite")
    private val allowedRoots = setOf("ble", "ble-testing")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "settings.gradle.kts not found above ${File("").absolutePath}" }
    }

    @Test
    fun nothingOutsideBleReferencesRawGattWrites() {
        val root = repoRoot()
        val offenders = root.listFiles { f -> f.isDirectory }.orEmpty()
            .filter { it.name !in allowedRoots && !it.name.startsWith(".") && it.name != "build" }
            .flatMap { module ->
                module.walkTopDown()
                    .onEnter { it.name != "build" && it.name != ".gradle" }
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { f -> banned.any { f.readText().contains(it) } }
                    .map { it.relativeTo(root).path }
                    .toList()
            }
        assertTrue(offenders.isEmpty(), "Raw GATT write API referenced outside :ble: $offenders")
    }
}
