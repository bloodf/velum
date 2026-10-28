package dev.velum.app.ble

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compile-time negative checks from **outside** `:ble` (this is `:composeApp`).
 *
 * 1. Calling `writeSealedAlreadyGuarded` on each concrete transport
 *    (Fake / Stub / Kable) must fail to compile.
 * 2. Passing a [dev.velum.protocol.SessionCipher] into
 *    [dev.velum.ble.PlaudBleTransport.openEncryptedSession] must fail to compile.
 *
 * Locates `kotlin-compiler-embeddable` from the local Gradle cache (no build-file
 * changes).
 */
class BleSealedWriteCompileGuardTest {
    @Test
    fun writeSealedAlreadyGuarded_unresolvedOnFakeBleTransport() {
        assertProbeFailsToCompile(
            fileName = "FakeSealedWriteProbe.kt",
            source = """
                package probe
                import dev.velum.ble.fake.FakeBleTransport
                import kotlinx.coroutines.runBlocking
                fun main() = runBlocking {
                    FakeBleTransport().writeSealedAlreadyGuarded("write", byteArrayOf(1))
                }
            """.trimIndent(),
            mustMention = "writeSealedAlreadyGuarded",
        )
    }

    @Test
    fun writeSealedAlreadyGuarded_unresolvedOnStubBleTransport() {
        assertProbeFailsToCompile(
            fileName = "StubSealedWriteProbe.kt",
            source = """
                package probe
                import dev.velum.ble.StubBleTransport
                import kotlinx.coroutines.runBlocking
                fun main() = runBlocking {
                    StubBleTransport().writeSealedAlreadyGuarded("write", byteArrayOf(1))
                }
            """.trimIndent(),
            // Class is internal to :ble — either the type or the method is invisible.
            mustMentionAny = listOf("StubBleTransport", "writeSealedAlreadyGuarded"),
        )
    }

    @Test
    fun writeSealedAlreadyGuarded_unresolvedOnKableBleTransport() {
        assertProbeFailsToCompile(
            fileName = "KableSealedWriteProbe.kt",
            source = """
                package probe
                import dev.velum.ble.KableBleTransport
                import kotlinx.coroutines.runBlocking
                fun main() = runBlocking {
                    KableBleTransport().writeSealedAlreadyGuarded("write", byteArrayOf(1))
                }
            """.trimIndent(),
            mustMentionAny = listOf("KableBleTransport", "writeSealedAlreadyGuarded"),
        )
    }

    @Test
    fun openEncryptedSession_withSessionCipher_unresolvedOutsideBle() {
        assertProbeFailsToCompile(
            fileName = "OpenEncryptedSessionProbe.kt",
            source = """
                package probe
                import dev.velum.ble.PlaudBleTransport
                import dev.velum.ble.createBleTransport
                import dev.velum.ble.crypto.createSessionCipher
                import dev.velum.protocol.Protocol20SessionSecret

                fun main() {
                    val secret = Protocol20SessionSecret(
                        key = ByteArray(32),
                        nonce = ByteArray(12),
                        aad = ByteArray(12),
                    )
                    PlaudBleTransport(createBleTransport()).openEncryptedSession(
                        createSessionCipher(),
                        secret,
                    )
                }
            """.trimIndent(),
            mustMention = "openEncryptedSession",
        )
    }

    private fun assertProbeFailsToCompile(
        fileName: String,
        source: String,
        mustMention: String? = null,
        mustMentionAny: List<String> = emptyList(),
    ) {
        val work = Files.createTempDirectory("ble-sealed-compile-guard").toFile()
        try {
            val probe = File(work, fileName)
            probe.writeText(source)

            val compileClasspath = testRuntimeClasspath()
            assertTrue(
                compileClasspath.any { it.contains("ble") || it.endsWith("classes") },
                "expected :ble on the unit-test classpath, got: ${compileClasspath.take(8)}…",
            )

            val kotlinVersion = "2.2.21"
            val compilerJar = findGradleJar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlinVersion)
                ?: fail("kotlin-compiler-embeddable-$kotlinVersion.jar not found under ~/.gradle/caches")
            val stdlibJar = findGradleJar("org.jetbrains.kotlin", "kotlin-stdlib", kotlinVersion)
                ?: fail("kotlin-stdlib-$kotlinVersion.jar not found under ~/.gradle/caches")
            val reflectJar = findGradleJar("org.jetbrains.kotlin", "kotlin-reflect", kotlinVersion)
                ?: fail("kotlin-reflect-$kotlinVersion.jar not found under ~/.gradle/caches")
            val daemonJar = findGradleJar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", kotlinVersion)
            val coroutinesJar = findGradleArtifactJar(
                groupPath = "org.jetbrains.kotlinx",
                artifact = "kotlinx-coroutines-core-jvm",
            ) ?: fail("kotlinx-coroutines-core-jvm jar not found under ~/.gradle/caches")

            val outDir = File(work, "out").also { it.mkdirs() }
            val compilerCp = listOfNotNull(
                compilerJar,
                stdlibJar,
                reflectJar,
                daemonJar,
                coroutinesJar,
            ).joinToString(File.pathSeparator) { it.absolutePath }

            val javaHome = System.getProperty("java.home")
            val javaBin = File(javaHome, "bin/java").takeIf { it.exists() }
                ?: File(javaHome, "bin/java.exe")

            val command = listOf(
                javaBin.absolutePath,
                "-cp",
                compilerCp,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-classpath",
                compileClasspath.joinToString(File.pathSeparator),
                "-no-stdlib",
                "-no-reflect",
                "-jvm-target",
                "17",
                "-d",
                outDir.absolutePath,
                probe.absolutePath,
            )

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .directory(work)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()

            assertFalse(
                exit == 0,
                "probe must fail to compile; kotlinc exited 0.\n$output",
            )
            val lower = output.lowercase()
            val looksUnresolved =
                "unresolved" in lower ||
                    "cannot access" in lower ||
                    "invisible" in lower ||
                    "public /*package*/" in lower
            assertTrue(
                looksUnresolved,
                "expected unresolved/invisible member error, got exit=$exit:\n$output",
            )
            if (mustMention != null) {
                assertTrue(
                    mustMention.lowercase() in lower,
                    "expected diagnostic to mention '$mustMention', got exit=$exit:\n$output",
                )
            }
            if (mustMentionAny.isNotEmpty()) {
                assertTrue(
                    mustMentionAny.any { it.lowercase() in lower },
                    "expected diagnostic to mention one of $mustMentionAny, got exit=$exit:\n$output",
                )
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun testRuntimeClasspath(): List<String> {
        val paths = linkedSetOf<String>()
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) {
                loader.urLs.forEach { url ->
                    if (url.protocol == "file") {
                        paths += File(url.toURI()).absolutePath
                    }
                }
            }
            loader = loader.parent
        }
        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { paths += File(it).absolutePath }
        return paths.toList()
    }

    private fun findGradleJar(groupPath: String, artifact: String, version: String): File? {
        val home = File(
            System.getProperty("user.home"),
            ".gradle/caches/modules-2/files-2.1/$groupPath/$artifact/$version",
        )
        if (!home.isDirectory) return null
        val exact = "$artifact-$version.jar"
        return home.walkTopDown().firstOrNull { it.isFile && it.name == exact }
    }

    private fun findGradleArtifactJar(groupPath: String, artifact: String): File? {
        val home = File(
            System.getProperty("user.home"),
            ".gradle/caches/modules-2/files-2.1/$groupPath/$artifact",
        )
        if (!home.isDirectory) return null
        return home.walkTopDown()
            .filter { it.isFile && it.name.startsWith("$artifact-") && it.name.endsWith(".jar") }
            .maxByOrNull { it.lastModified() }
    }
}
