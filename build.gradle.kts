plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.roborazzi) apply false
}

// Dependency CVE posture: see docs/DEPENDENCY_AUDIT.md (OSV spot-check; prefer Renovate over OWASP plugin).

// Align app and instrumented-test stdlib with the catalog's compiler version (#106).
// Verified metadata cause: AGP 8.10.1's bundled R8 could not parse Kotlin 2.3.0
// metadata (litertlm 0.16.1 and transitive stdlib 2.3.x), which forced
// -Xskip-metadata-version-check and a stdlib downgrade. Fix: AGP 8.13.2
// (R8 8.13.19) + Kotlin 2.3.21, no skip flags.
// A dependency requesting a newer stdlib than the compiler fails the build
// instead of being silently downgraded.
val kotlinStdlibPin = libs.versions.kotlin.get()
fun kotlinVersionParts(v: String) = v.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
fun isNewerKotlinVersion(candidate: String, base: String): Boolean {
    val c = kotlinVersionParts(candidate)
    val b = kotlinVersionParts(base)
    return c.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x > y } ?: false
}
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" &&
                requested.name.startsWith("kotlin-stdlib")
            ) {
                val requestedVersion = requested.version
                if (requestedVersion != null && isNewerKotlinVersion(requestedVersion, kotlinStdlibPin)) {
                    throw GradleException(
                        "${requested.group}:${requested.name}:$requestedVersion is newer than the " +
                            "Kotlin compiler $kotlinStdlibPin; upgrade Kotlin instead of downgrading stdlib (#106)",
                    )
                }
                useVersion(kotlinStdlibPin)
                because("Align kotlin-stdlib with Kotlin compiler for R8 metadata (#106)")
            }
        }
    }
}
