package dev.velum.ble

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fake BLE transports/peripherals and Wi-Fi fakes live in `:ble-testing` and must never ship (#111).
 * Source-level half of the guard; `:composeApp:verifyNoTestFakesShipped` checks resolved classpaths.
 */
class BleFakesNotInProductionTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val productionSources: List<File> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name != "ble-testing" }
        .flatMap { module -> File(module, "src").listFiles().orEmpty().toList() }
        .filter { it.isDirectory && !it.name.endsWith("Test") }
        .flatMap { set -> set.walkTopDown().filter { it.extension == "kt" }.toList() }

    @Test
    fun scan_covers_ble_production_sources() {
        assertTrue(
            productionSources.any { it.path.endsWith("ble/src/commonMain/kotlin/dev/velum/ble/BleTransport.kt") },
            "scan root wrong: $root",
        )
    }

    @Test
    fun only_allowlisted_ble_transports_in_production() {
        assertEquals(
            listOf(
                "ble/src/commonMain/kotlin/dev/velum/ble/StubBleTransport.kt:StubBleTransport",
                "ble/src/mobileMain/kotlin/dev/velum/ble/KableBleTransport.kt:KableBleTransport",
            ),
            implementationsOf("BleTransport"),
            "Only real transports may implement BleTransport in production; fakes go in :ble-testing",
        )
    }

    @Test
    fun only_allowlisted_wifi_joiners_in_production() {
        assertEquals(
            listOf(
                "ble/src/androidMain/kotlin/dev/velum/ble/wifi/AndroidWifiSoftApJoiner.kt:AndroidWifiSoftApJoiner",
                "ble/src/commonMain/kotlin/dev/velum/ble/wifi/WifiFastTransfer.kt:UnsupportedWifiSoftApJoiner",
            ),
            implementationsOf("WifiSoftApJoiner"),
            "Only real joiners may implement WifiSoftApJoiner in production; fakes go in :ble-testing",
        )
    }

    /** `path:Name` for every class/object (incl. anonymous) whose supertype list names [type]. */
    private fun implementationsOf(type: String): List<String> {
        val supertype = Regex("""(?:\)|\b(?:class|object)(?:\s+\w+)?)\s*:[^=]*\b$type\b""")
        return productionSources.flatMap { file ->
            val lines = file.readLines()
            lines.indices.filter { "fun " !in lines[it] && supertype.containsMatchIn(lines[it]) }.map { index ->
                val name = declaration.findAll(lines.take(index + 1).joinToString("\n")).lastOrNull()
                    ?.groupValues?.get(1)
                val anonymous = Regex("""\bobject\s*:""").containsMatchIn(lines[index])
                "${file.relativeTo(root).path}:${if (anonymous) "<anonymous object>" else name ?: "<unknown>"}"
            }
        }.sorted()
    }

    @Test
    fun no_fake_peripheral_wifi_or_fake_package_in_production() {
        val offenders = productionSources.filter { file ->
            val text = file.readText()
            fakePeripheral.containsMatchIn(text) || fakePackage.containsMatchIn(text)
        }.map { it.relativeTo(root).path }
        assertTrue(offenders.isEmpty(), "Fake BLE code in production sources (move to :ble-testing): $offenders")
    }

    @Test
    fun ble_testing_only_declared_in_test_source_sets() {
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "ble-testing" }
            .filter { it.name == "build.gradle.kts" }
            .flatMap { file ->
                val lines = file.readLines()
                lines.indices.filter { "project(\":ble-testing\")" in lines[it] }.mapNotNull { index ->
                    val sourceSet = (index downTo 0).firstNotNullOfOrNull { i ->
                        sourceSetOpener.find(lines[i])?.groupValues?.get(1)
                    }
                    if (sourceSet?.endsWith("Test") == true) null else "${file.relativeTo(root).path}:${index + 1} ($sourceSet)"
                }
            }.toList()
        assertTrue(offenders.isEmpty(), ":ble-testing outside a *Test source set: $offenders")
    }

    private companion object {
        val declaration = Regex("""\b(?:class|object)\s+(\w+)""")
        val fakePeripheral = Regex("""class\s+Fake(?:\w*Peripheral|Wifi\w*)\b""")
        val fakePackage = Regex("""^\s*(package|import)\s+dev\.velum\.ble\.fake\b""", RegexOption.MULTILINE)
        val sourceSetOpener = Regex("""\b(\w+(?:Main|Test))\b[^{]*\{\s*$""")
    }
}
