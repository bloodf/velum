package dev.velum.ble

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #111: fake BLE/Wi-Fi transports and peripherals live only in `:ble-testing` (consumed through test
 * source sets). This fails if one is declared in any production source set of any module.
 * Complements `verifyNoTestFakesShipped`, which checks classpaths, not sources.
 *
 * Heuristic: flags declarations named `Fake...` with a BLE/Plaud/Wi-Fi/transport/peripheral word, and
 * `BleTransport` implementers other than the two real transports.
 */
class NoFakesInProductionSourceSetsTest {
    private val fakeDecl = Regex("""\b(class|object)\s+Fake\w*(Ble|Plaud|Wifi|Transport|Peripheral)\w*""")
    private val transportImpl = Regex("""\b(class|object)\s+(\w+)[^{=\n]*:[^{=\n]*\bBleTransport\b""")
    private val realTransports = setOf("KableBleTransport", "StubBleTransport")

    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun productionSources(root: File): List<File> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name != "ble-testing" && File(it, "src").isDirectory }
        .flatMap { File(it, "src").listFiles().orEmpty().toList() }
        .filter { it.isDirectory && !it.name.endsWith("Test") && !it.name.endsWith("TestFixtures") }
        .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun offences(path: String, text: String): List<String> =
        fakeDecl.findAll(text).map { "$path: ${it.value}" }.toList() +
            transportImpl.findAll(text).filter { it.groupValues[2] !in realTransports }.map { "$path: ${it.value}" }.toList()

    @Test
    fun no_fake_ble_transport_or_peripheral_in_production_source_sets() {
        val root = repoRoot()
        val sources = productionSources(root)
        assertTrue(sources.any { it.name == "KableBleTransport.kt" }, "scan root wrong: $root")
        val found = sources.flatMap { offences(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
        assertTrue(found.isEmpty(), "Test doubles belong in :ble-testing, not production source sets:\n" + found.joinToString("\n"))
    }

    @Test
    fun detector_flags_fakes_and_foreign_transports_but_not_real_ones() {
        assertTrue(offences("x.kt", "class FakeBleTransport(val a: Int) : BleTransport {").isNotEmpty())
        assertEquals(1, offences("x.kt", "object FakePlaudPeripheral").size)
        assertEquals(1, offences("x.kt", "internal class MyDouble : BleTransport {").size)
        assertEquals(0, offences("x.kt", "class KableBleTransport : BleTransport {").size)
        assertEquals(0, offences("x.kt", "class FakeClock").size)
    }
}
