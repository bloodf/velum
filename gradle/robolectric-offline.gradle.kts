// Applied by every module whose Android unit tests use Robolectric (:composeApp, :ble).
//
// Robolectric 4.14.1 downloads its instrumented android-all jars at test time with no retry and no CI
// cache, so one Maven Central 429 fails whole tests (MavenArtifactFetcher IOException). Resolve them
// through Gradle (cached) and run Robolectric offline from that directory. Gradle does not verify
// them (no dependency-verification file), so the sync pins SHA-256 values published by Maven Central
// itself (<jar>.sha256 on repo.maven.apache.org and repo1.maven.org) and fails on a mismatch.
//
// The two jars are Robolectric 4.14.1's SDK 34 and 35. Change them together with libs.versions.robolectric.
// A missing jar fails offline with its name.
import java.security.MessageDigest

val androidAllSha256 = mapOf(
    "14-robolectric-10818077-i7" to "3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701",
    "15-robolectric-12650502-i7" to "33c2d7cc4186d4605269d5d411fa1818972c5b30a9f70bfbbd3d430b076af52f",
)

// One configuration per SDK: two versions of one module in a single configuration would conflict-resolve to one.
val robolectricAndroidAll = androidAllSha256.keys.map { version ->
    configurations.create("robolectricAndroidAll${version.substringBefore('-')}") {
        isTransitive = false
        dependencies.add(project.dependencies.create("org.robolectric:android-all-instrumented:$version"))
    }
}
val syncRobolectricAndroidAll = tasks.register<Sync>("syncRobolectricAndroidAll") {
    robolectricAndroidAll.forEach { from(it) }
    into(layout.buildDirectory.dir("robolectric-android-all"))
    val expected = androidAllSha256
    doLast {
        expected.forEach { (version, sha256) ->
            val jar = destinationDir.resolve("android-all-instrumented-$version.jar")
            check(jar.isFile) { "Robolectric jar missing: ${jar.name}" }
            val actual = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "SHA-256 mismatch for ${jar.name}: expected $sha256 but was $actual" }
        }
    }
}
tasks.withType<Test>().configureEach {
    if (name.endsWith("UnitTest")) {
        dependsOn(syncRobolectricAndroidAll)
        val dir = syncRobolectricAndroidAll.map { it.destinationDir }
        systemProperty("robolectric.offline", "true")
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Drobolectric.dependency.dir=${dir.get().absolutePath}")
        }
    }
}
