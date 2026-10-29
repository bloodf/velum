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

// Pin kotlin-stdlib to the compiler version (#106). A transitive 2.3.x stdlib
// makes R8 emit "metadata is 2.3.0, expected version is 2.1.0" parse errors
// against AGP 8.10's kotlin-metadata reader.
val kotlinStdlibPin = "2.2.21"
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" &&
                requested.name.startsWith("kotlin-stdlib")
            ) {
                useVersion(kotlinStdlibPin)
                because("Align kotlin-stdlib with Kotlin compiler for R8 metadata (#106)")
            }
        }
    }
}
