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
val kotlinStdlibPin = libs.versions.kotlin.get()
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
