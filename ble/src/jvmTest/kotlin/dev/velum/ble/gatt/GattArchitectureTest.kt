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
    // Test double that only IMPLEMENTS the link (cannot construct writes: constructors are internal).
    private val allowedFiles = setOf(
        "composeApp/src/androidUnitTest/kotlin/dev/velum/app/di/DeviceCredentialFailureTest.kt",
    )
    // Test doubles inside :ble that wrap a transport and forward writeRaw (they cannot build writes).
    private val recordingDoubles = setOf("SimulatedPeripheralScenariosTest.kt")
    private val allowedRoots = setOf("ble", "ble-testing")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "settings.gradle.kts not found above ${File("").absolutePath}" }
    }

    @Test
    fun insideBle_onlyGuardedLinkBuildsWritesOrCallsWriteRaw() {
        val bleSrc = File(repoRoot(), "ble/src")
        val files = bleSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "GattArchitectureTest.kt" }.toList()
        // Construction ban has no exceptions except GuardedLink.kt itself.
        val builders = files.filter { it.name != "GuardedLink.kt" }
            .filter { f -> f.readText().let { "PlainWrite(" in it || "SealedWrite(" in it } }.map { it.name }
        assertTrue(builders.isEmpty(), "Only GuardedLink.kt may build writes: $builders")
        // Forwarding test doubles are exempt from the writeRaw CALL ban by name only.
        val callers = files.filter { it.name != "GuardedLink.kt" && it.name !in recordingDoubles }
            .filter { ".writeRaw(" in it.readText() }.map { it.name }
        assertTrue(callers.isEmpty(), "Only GuardedLink.kt may call writeRaw: $callers")
        val implementers = setOf(
            "Gatt.kt", "GattArchitectureTest.kt", "SimulatedPeripheralScenariosTest.kt", "StubBleTransport.kt", "FakeBleTransport.kt", "KableBleTransport.kt", "GuardedLinkTest.kt",
        )
        val overriders = bleSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "fun writeRaw" in it.readText() && it.name !in implementers }.map { it.name }.toList()
        assertTrue(overriders.isEmpty(), "writeRaw implemented outside transports: $overriders")
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
                    .filter { it.relativeTo(root).path !in allowedFiles }
                    .filter { f -> banned.any { f.readText().contains(it) } }
                    .map { it.relativeTo(root).path }
                    .toList()
            }
        assertTrue(offenders.isEmpty(), "Raw GATT write API referenced outside :ble: $offenders")
    }

    @Test
    fun onlyBleModuleDeclaresKable() {
        val root = repoRoot()
        val offenders = root.listFiles { f -> f.isDirectory && f.name != "ble" }.orEmpty().flatMap { module ->
            module.walkTopDown().onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.name == "build.gradle.kts" && it.readText().contains("kable", ignoreCase = true) }
                .map { it.relativeTo(root).path }.toList()
        }
        assertTrue(offenders.isEmpty(), "Kable may only be declared by :ble: $offenders")
    }
}
