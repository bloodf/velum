package dev.velum.ble.gatt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Choke-point check (#19): only :ble (and the :ble-testing fakes module, once it exists) may touch
 * raw GATT writes. Drivers/app code receive BleTransport or GuardedLink and nothing else.
 */
class GattArchitectureTest {
    private val banned = listOf("writeRaw", "SealedWrite(", "PlainWrite(", "GattWrite", "sendEncryptedFrame")
    // Test double that only IMPLEMENTS the link (cannot construct writes: constructors are internal).
    private val allowedFiles = setOf(
        "composeApp/src/androidUnitTest/kotlin/dev/velum/app/di/DeviceCredentialFailureTest.kt",
    )
    // Exact repo-relative paths, so a same-named file elsewhere gets no exemption.
    private val guardedLink = "ble/src/commonMain/kotlin/dev/velum/ble/gatt/GuardedLink.kt"
    private val thisTest = "ble/src/jvmTest/kotlin/dev/velum/ble/gatt/GattArchitectureTest.kt"
    // Test double inside :ble that wraps a transport and forwards writeRaw (it cannot build writes).
    private val recordingDouble = "ble/src/jvmTest/kotlin/dev/velum/ble/SimulatedPeripheralScenariosTest.kt"
    private val implementers = setOf(
        "ble/src/commonMain/kotlin/dev/velum/ble/gatt/Gatt.kt",
        "ble/src/commonMain/kotlin/dev/velum/ble/StubBleTransport.kt",
        "ble/src/mobileMain/kotlin/dev/velum/ble/KableBleTransport.kt",
        "ble/src/jvmTest/kotlin/dev/velum/ble/gatt/GuardedLinkTest.kt",
        "ble/src/jvmTest/kotlin/dev/velum/ble/gatt/PocketProfileTest.kt", // counting double, cannot build writes
        "ble/src/jvmTest/kotlin/dev/velum/ble/gatt/OmiProfileTest.kt", // recording double, cannot build writes
        "ble-testing/src/commonMain/kotlin/dev/velum/ble/fake/FakeBleTransport.kt",
        recordingDouble,
    )
    private val wifiSendCallers = setOf(
        "ble/src/commonMain/kotlin/dev/velum/ble/wifi/WifiFastTransfer.kt",
        "ble/src/jvmTest/kotlin/dev/velum/ble/wifi/PreamblePerConnectionTest.kt",
    )
    private val allowedRoots = setOf("ble", "ble-testing")
    // A call (`.f(`) or a bound/unbound reference (`::f`, `x::f`); a bare declaration `fun f` is not a use.
    private val wifiSendUse = Regex("""(\.|::)\s*sendEncryptedFrame\b""")

    private fun referencesWifiSend(text: String) = wifiSendUse.containsMatchIn(text)

    @Test
    fun wifiSendDetector_catchesCallsAndReferences_notDeclarations() {
        assertTrue(referencesWifiSend("server.sendEncryptedFrame(b)"))
        assertTrue(referencesWifiSend("val f = server::sendEncryptedFrame"))
        assertTrue(referencesWifiSend("val g = WifiServer::sendEncryptedFrame"))
        assertTrue(referencesWifiSend("server\n    .sendEncryptedFrame(b)"))
        assertTrue(!referencesWifiSend("override suspend fun sendEncryptedFrame(b: ByteArray) {}"))
        assertTrue(!referencesWifiSend("// mentions sendEncryptedFrame in prose"))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "settings.gradle.kts not found above ${File("").absolutePath}" }
    }

    private fun File.isSource() = isFile && extension in setOf("kt", "java")

    @Test
    fun insideBle_onlyGuardedLinkBuildsWritesOrCallsWriteRaw() {
        val root = repoRoot()
        val files = listOf("ble/src", "ble-testing/src")
            .flatMap { File(root, it).walkTopDown().filter { f -> f.isSource() }.toList() }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .filter { (path, _) -> path != thisTest }
        assertTrue(guardedLink in files.map { it.first }, "scan root wrong: $root")
        // Construction ban has no exceptions except GuardedLink.kt itself.
        val builders = files.filter { (path, text) ->
            path != guardedLink && ("PlainWrite(" in text || "SealedWrite(" in text)
        }.map { it.first }
        assertTrue(builders.isEmpty(), "Only GuardedLink.kt may build writes: $builders")
        // The forwarding test double is exempt from the writeRaw CALL ban by exact path only.
        val callers = files.filter { (path, text) ->
            path != guardedLink && path != recordingDouble && ".writeRaw(" in text
        }.map { it.first }
        assertTrue(callers.isEmpty(), "Only GuardedLink.kt may call writeRaw: $callers")
        val overriders = files.filter { (path, text) ->
            path !in implementers && (if (path.endsWith(".java")) "writeRaw(" in text else "fun writeRaw" in text)
        }.map { it.first }
        assertTrue(overriders.isEmpty(), "writeRaw implemented outside transports: $overriders")
        // The Wi-Fi socket write: only the session (which guards and checks before it) and the one test that
        // exercises a server directly may call it. Servers and fakes may declare it, not call it.
        val wifiCallers = files.filter { (path, text) ->
            path !in wifiSendCallers && referencesWifiSend(text)
        }.map { it.first }
        assertTrue(wifiCallers.isEmpty(), "Only WifiFastTransferSession may call sendEncryptedFrame: $wifiCallers")
    }

    @Test
    fun nothingOutsideBleReferencesRawGattWrites() {
        val root = repoRoot()
        val offenders = root.listFiles { f -> f.isDirectory }.orEmpty()
            .filter { it.name !in allowedRoots && !it.name.startsWith(".") && it.name != "build" }
            .flatMap { module ->
                module.walkTopDown()
                    .onEnter { it.name != "build" && it.name != ".gradle" }
                    .filter { it.isSource() }
                    .filter { it.relativeTo(root).invariantSeparatorsPath !in allowedFiles }
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
