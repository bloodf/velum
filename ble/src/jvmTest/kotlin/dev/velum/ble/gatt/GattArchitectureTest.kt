package dev.velum.ble.gatt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // The guard API itself: outside :ble nobody may build a GuardedLink or supply a WriteGuard (a wrapper that edits
    // write.bytes after the guard, or a weaker guard, is how the choke point would be bypassed). Code uses only, so a
    // doc comment that names PlaudWriteGuard does not trip it.
    private val guardApiUse = Regex("""\bGuardedLink\s*\(|\bWriteGuard\s*[({]|:\s*WriteGuard\b|\bPlaudWriteGuard\s*\.""")

    private fun referencesGuardApi(text: String) = guardApiUse.containsMatchIn(text)

    @Test
    fun guardApiDetector_catchesUses_notComments() {
        assertTrue(referencesGuardApi("val l = GuardedLink(transport, profile)"))
        assertTrue(referencesGuardApi("val g = WriteGuard { }"))
        assertTrue(referencesGuardApi("object X : WriteGuard {"))
        assertTrue(referencesGuardApi("PlaudWriteGuard.check(bytes)"))
        assertTrue(!referencesGuardApi("// PlaudWriteGuard in :ble rejects reads of them too"))
        assertTrue(!referencesGuardApi("see `PlaudWriteGuard` in :ble"))
    }

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
        // The fakes module is test support for other modules: it may not build a GuardedLink or supply a guard either.
        val fakesUsingGuardApi = files.filter { (path, text) -> path.startsWith("ble-testing/") && referencesGuardApi(text) }.map { it.first }
        assertTrue(fakesUsingGuardApi.isEmpty(), "ble-testing must not use the guard API: $fakesUsingGuardApi")
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
                    .filter { f -> f.readText().let { text -> banned.any { text.contains(it) } || referencesGuardApi(text) } }
                    .map { it.relativeTo(root).path }
                    .toList()
            }
        assertTrue(offenders.isEmpty(), "Raw GATT write API referenced outside :ble: $offenders")
    }

    // KableBleTransport is not run by any test here (it needs a radio), so its two write checks are pinned by source. Not a
    // parser: each check must be a whole line at the function body's own indent, which leaves out branches, lambdas and
    // strings. A brace-less `if` on the line above, or an early return, would still pass.
    private val kableWriteChecks = mapOf(
        "requireTransportAllowed" to """requireTransportAllowed\(write\)""",
        "requireSameLink" to """requireSameLink\(write\.linkGeneration, .+\)""",
    )

    /** What is wrong with writeRaw in [source]: a check missing or after the first send, no send, or not exactly one writeRaw. */
    private fun writeRawProblems(source: String): List<String> {
        val code = source
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "")
            .replace(Regex("//.*"), "")
        val signature = "override suspend fun writeRaw("
        val count = code.windowed(signature.length).count { it == signature }
        if (count != 1) return listOf("$count writeRaw functions")
        val start = code.indexOf(signature)
        val indent = start - (code.lastIndexOf('\n', start) + 1) + 4
        val open = code.indexOf('{', start)
        var depth = 0
        var end = open
        while (end < code.length) {
            if (code[end] == '{') depth++
            if (code[end] == '}' && --depth == 0) break
            end++
        }
        val body = code.substring(open, end)
        val send = body.indexOf(".write(")
        if (send < 0) return listOf("no send")
        return kableWriteChecks.filter { (_, call) ->
            val line = Regex("(?m)^ {$indent}$call[ \\t]*$").find(body)
            line == null || line.range.first > send
        }.keys.toList()
    }

    @Test
    fun writeRawDetector_needsWholeLineChecksBeforeTheSend() {
        val first = "        requireTransportAllowed(write)"
        val second = "        requireSameLink(write.linkGeneration, g)"
        val send = "        p.write(c, b, t)"
        val both = listOf("requireTransportAllowed", "requireSameLink")
        fun writeRaw(vararg lines: String) = "    override suspend fun writeRaw(write: W) {\n${lines.joinToString("\n")}\n    }\n"

        assertEquals(emptyList(), writeRawProblems(writeRaw("        if (x) { y() }", first, second, send)))
        assertEquals(both.take(1), writeRawProblems(writeRaw(second, send)))
        assertEquals(both.drop(1), writeRawProblems(writeRaw(first, send)))
        assertEquals(both, writeRawProblems("    fun other() {\n$first\n$second\n    }\n" + writeRaw(send)))
        assertEquals(both, writeRawProblems(writeRaw(send) + "    fun later() {\n$first\n$second\n    }\n"))
        // A branch, an uninvoked lambda, strings and comments do not run the check.
        assertEquals(both.take(1), writeRawProblems(writeRaw("        if (x && false) {", "    $first", "        }", second, send)))
        assertEquals(both.take(1), writeRawProblems(writeRaw("        if (x && false) { requireTransportAllowed(write) }", second, send)))
        assertEquals(both.drop(1), writeRawProblems(writeRaw(first, "        val unused = { requireSameLink(write.linkGeneration, g) }", send)))
        assertEquals(both.drop(1), writeRawProblems(writeRaw(first, "        val unused = {", "    $second", "        }", send)))
        assertEquals(both.take(1), writeRawProblems(writeRaw("        val s = \"requireTransportAllowed(write)\"", second, send)))
        assertEquals(both.take(1), writeRawProblems(writeRaw("        val s = \"\"\"", first, "\"\"\"", second, send)))
        assertEquals(both.take(1), writeRawProblems(writeRaw("        /* requireTransportAllowed(write) */", second, send)))
        assertEquals(both.take(1), writeRawProblems(writeRaw("        /*", first, "        */", second, send)))
        assertEquals(both.take(1), writeRawProblems(writeRaw("        // requireTransportAllowed(write)", second, send)))
        // Ordering holds whatever the send is called on, and a body with no send is not a pass.
        assertEquals(both.drop(1), writeRawProblems(writeRaw(first, "        val q = p", "        q.write(c, b, t)", second)))
        assertEquals(both.drop(1), writeRawProblems(writeRaw(first, send, second)))
        assertEquals(listOf("no send"), writeRawProblems(writeRaw(first, second)))
        // Exactly one writeRaw.
        assertEquals(listOf("0 writeRaw functions"), writeRawProblems("class NoWriteRaw"))
        assertEquals(listOf("2 writeRaw functions"), writeRawProblems(writeRaw(first, second, send) + writeRaw(send)))
    }

    @Test
    fun kableWriteRaw_runsBothChecksBeforeItSends() {
        val source = File(repoRoot(), "ble/src/mobileMain/kotlin/dev/velum/ble/KableBleTransport.kt").readText()
        val problems = writeRawProblems(source)
        assertTrue(problems.isEmpty(), "KableBleTransport.writeRaw must run both checks, as whole lines, before its send: $problems")
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
