import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.roborazzi)
}

val identityPersistenceTestEnabled = providers.gradleProperty("velumIdentityPersistenceTest")
    .map { it.toBooleanStrict() }.getOrElse(false)

val releaseSigningVariables = listOf(
    "VELUM_UPLOAD_STORE_FILE",
    "VELUM_UPLOAD_STORE_PASSWORD",
    "VELUM_UPLOAD_KEY_ALIAS",
    "VELUM_UPLOAD_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.associateWith {
    providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank)
}
val missingReleaseSigningVariables = releaseSigningVariables.filter { releaseSigningValues[it] == null }
// -Pvelum.unsignedRelease=true is for F-Droid only: it builds the release APK without our upload
// key and F-Droid signs the result. Never combined with real signing variables, so a misconfigured
// shipping build cannot silently come out unsigned.
val unsignedRelease = providers.gradleProperty("velum.unsignedRelease")
    .map { it.toBooleanStrict() }.getOrElse(false)
val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Require explicit upload-key configuration for shipping release builds."
    val missingVariables = missingReleaseSigningVariables
    val unsigned = unsignedRelease
    doLast {
        check(!unsigned || missingVariables.size == releaseSigningVariables.size) {
            "-Pvelum.unsignedRelease=true cannot be combined with VELUM_UPLOAD_* variables."
        }
        check(unsigned || missingVariables.isEmpty()) {
            "Release signing is not configured. Set ${missingVariables.joinToString()} " +
                "to build a shipping release, or pass -Pvelum.unsignedRelease=true for an unsigned " +
                "F-Droid build. For local/CI R8 checks without release secrets, " +
                "use :composeApp:assembleMinifiedTest (never distribute that test APK)."
        }
    }
}

// Require signing for every shipping packaging path, including direct AGP task
// invocations. Pure release compilation/R8/lint can still run without secrets.
tasks.matching {
    it.name in setOf(
        "assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle",
        "signReleaseBundle", "validateSigningRelease",
    )
}.configureEach {
    dependsOn(validateReleaseSigning)
}
// When packaging is requested, fail before compiling release sources. This
// ordering alone does not schedule the guard for compile/lint-only requests.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    mustRunAfter(validateReleaseSigning)
}

// Per-ABI APKs (#91): opt in with -Pvelum.abiSplits=true (shipping APK builds via
// scripts/check-release-signing.sh / CONTRIBUTING.md). Off by default so debug and
// minifiedTest keep one universal APK (CI globs, x86_64 emulator). Split builds also emit a
// universal APK (all ABIs incl. x86/x86_64) for sideload / ChromeOS. Play gets the AAB.
val abiSplitsEnabled = providers.gradleProperty("velum.abiSplits")
    .map { it.toBooleanStrict() }.getOrElse(false)

// Release versionCode = defaultConfig.versionCode (a literal multiple of 10, so F-Droid can read it)
// + ABI digit. Universal (and the AAB / non-split APK) = +0, so a device on the universal APK can
// move to its ABI APK of the same release; every output of the next release (+10) outranks every
// output of this one.
val abiVersionCodeDigit = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)

// Never combine ABI splits with an App Bundle in one invocation: fail loudly, never fall back.
val rejectAbiSplitsWithBundle by tasks.registering {
    group = "verification"
    description = "Fail when -Pvelum.abiSplits=true is combined with an App Bundle task."
    val splits = abiSplitsEnabled
    doLast {
        check(!splits) {
            "-Pvelum.abiSplits=true cannot be combined with bundle tasks. " +
                "Run assembleRelease (with the property) and bundleRelease (without it) separately."
        }
    }
}
val bundleTaskName = Regex("bundle(Debug|Release|MinifiedTest)|build(Debug|Release|MinifiedTest)PreBundle")
tasks.matching { bundleTaskName.matches(it.name) }.configureEach {
    dependsOn(rejectAbiSplitsWithBundle)
}

// Test fakes (:ble-testing, #111) must never reach a shipping runtime classpath.
val shippingClasspaths = listOf("releaseRuntimeClasspath", "minifiedTestRuntimeClasspath", "desktopRuntimeClasspath")
val verifyNoTestFakesShipped by tasks.registering {
    group = "verification"
    // debugRuntimeClasspath is deliberately not checked: debug APKs are never distributed.
    description = "Fail if :ble-testing is on a release, minifiedTest or desktop runtime classpath " +
        "(debug is deliberately unchecked: debug APKs are never distributed)."
    val roots = shippingClasspaths.map { name ->
        name to configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }
    }
    doLast {
        roots.forEach { (name, root) ->
            val seen = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
            val queue = ArrayDeque(listOf(root.get()))
            while (queue.isNotEmpty()) {
                val component = queue.removeFirst()
                if (!seen.add(component.id)) continue
                val id = component.id
                check(!(id is org.gradle.api.artifacts.component.ProjectComponentIdentifier && id.projectPath == ":ble-testing")) {
                    ":ble-testing leaked onto $name; depend on it only from test source sets."
                }
                component.dependencies
                    .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                    .forEach { queue.add(it.selected) }
            }
        }
    }
}
// Desktop: create(Release)Distributable and every package(Release)* distribution/installer/uber-jar task.
val desktopPackaging = Regex("""create(Release)?Distributable|package(Release)?(Distribution\w*|Dmg|Pkg|Msi|Exe|Deb|Rpm|AppImage|UberJar\w*)""")
tasks.matching {
    it.name in setOf("preReleaseBuild", "preMinifiedTestBuild", "check") || desktopPackaging.matches(it.name)
}.configureEach {
    dependsOn(verifyNoTestFakesShipped)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            export(project(":ai"))
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting
        val androidMain by getting
        // Identical JVM filesystem policy, without changing the Kotlin target hierarchy.
        androidMain.kotlin.srcDir("src/jvmSharedMain/kotlin")
        desktopMain.kotlin.srcDir("src/jvmSharedMain/kotlin")

        commonMain.dependencies {
            implementation(project(":core"))
            api(project(":ai"))
            implementation(project(":ble"))
            implementation(project(":plaud-protocol"))
            implementation(project(":recorder"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.compose.navigation)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlin.test)
            implementation(compose.ui)
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation("androidx.core:core-ktx:1.15.0")
            implementation("androidx.documentfile:documentfile:1.0.1")
            // Pin so R8 keeps Trace for connectedReleaseAndroidTest (AndroidJUnitRunner).
            implementation("androidx.tracing:tracing:1.2.0")
            // Pin 16 KB-aligned graphics-path (Compose may otherwise pull 1.0.1).
            implementation(libs.androidx.graphics.path)
        }
        val androidUnitTest by getting {
            kotlin.srcDir("src/jvmSharedTest/kotlin")
            dependencies {
                implementation(libs.kotlin.test)
                implementation(project(":ble-testing"))
                implementation(libs.junit)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.work.testing)
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.robolectric)
                implementation(libs.roborazzi)
                implementation(libs.roborazzi.compose)
                implementation(libs.roborazzi.rule)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidInstrumentedTest by getting {
            // These host-coordinated tests require explicit UUID/phase arguments.
            // Default test discovery retains only the original instrumented sources.
            if (identityPersistenceTestEnabled) kotlin.srcDir("src/identityPersistenceTest/kotlin")
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlin.test)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.test.uiautomator)
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.androidx.compose.ui.test.manifest)
                implementation("androidx.tracing:tracing:1.2.0")
                implementation(project(":ble-testing"))
            }
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        val desktopTest by getting {
            kotlin.srcDir("src/jvmSharedTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "dev.velum.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    // Default debug. Device-walk CI passes -PvelumTestBuildType=minifiedTest so
    // connectedMinifiedTestAndroidTest exercises a genuinely R8-minified +
    // obfuscated APK (initWith release + debug signing + proguard-test-rules.pro).
    // Shipping assembleRelease uses proguard-rules.pro alone (#106).
    //
    // OWNED BY #106 agent: do NOT flip back to minify-off connectedReleaseAndroidTest
    // or remove minifiedTest. CTO requires a genuine R8+obfuscation instrumented walk.
    val walkBuildType =
        (findProperty("velumTestBuildType") as String?)?.takeIf { it.isNotBlank() } ?: "debug"
    testBuildType = walkBuildType

    defaultConfig {
        applicationId = "dev.velum.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1000 // universal; keep a multiple of 10, ABI APKs add 1 (v7a) / 2 (arm64)
        versionName = "1.0.0"
        // Explicit test-APK selection only; the default runner and production app are unchanged.
        testInstrumentationRunner = if (identityPersistenceTestEnabled) "dev.velum.app.identity.IdentityPersistenceTestRunner"
        else "dev.velum.app.devicewalk.DeviceWalkTestRunner"
        // Prefer DSL over -Pandroid.testInstrumentationRunnerArguments.* (breaks config cache).
        if (!identityPersistenceTestEnabled) {
            testInstrumentationRunnerArguments += mapOf(
                "velumDeviceWalk" to "true",
                "class" to "dev.velum.app.devicewalk.DeviceWalkUiTest",
            )
        }
    }
    splits {
        abi {
            isEnable = abiSplitsEnabled
            reset()
            include(*abiVersionCodeDigit.keys.toTypedArray())
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs {
            // AGP 8.5.1+: uncompressed native libs so zipalign -P 16 applies (16 KB pages).
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // Fixed debug keystore so CI + local debug APKs share one signature (adb install -r).
    // Passwords are the public Android debug defaults; never use for release.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Upload key via CI / local secrets — never commit the keystore.
        // VELUM_UPLOAD_STORE_FILE, VELUM_UPLOAD_STORE_PASSWORD, VELUM_UPLOAD_KEY_ALIAS,
        // VELUM_UPLOAD_KEY_PASSWORD
        if (missingReleaseSigningVariables.isEmpty()) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("VELUM_UPLOAD_STORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("VELUM_UPLOAD_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("VELUM_UPLOAD_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("VELUM_UPLOAD_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // Never sign a shipping release with the public debug key (#90).
            // validateReleaseSigning fails release builds if configuration is incomplete.
            // Null with -Pvelum.unsignedRelease=true: AGP emits composeApp-release-unsigned.apk.
            signingConfig = if (unsignedRelease) null else signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Genuinely minified + obfuscated instrumented walk (#106): shipping R8
        // rules plus targeted test-only keeps. Debug-signed so CI can adb install
        // without the upload keystore. Library modules fall back to release.
        create("minifiedTest") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            // initWith(release) already attaches shipping proguard-rules.pro.
            proguardFile("proguard-test-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        animationsDisabled = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val universal = checkNotNull(android.defaultConfig.versionCode) { "defaultConfig.versionCode missing" }
        check(universal % 10 == 0) { "defaultConfig.versionCode must be a multiple of 10, was $universal" }
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            output.versionCode.set(universal + (abi?.let(abiVersionCodeDigit::getValue) ?: 0))
        }
    }
}

roborazzi {
    outputDir.set(file("build/outputs/roborazzi"))
}

compose {
    resources {
        packageOfResClass = "dev.velum.app.resources"
    }
}

compose.desktop {
    application {
        mainClass = "dev.velum.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Velum"
            packageVersion = "1.0.0"
            description = "Open-source local-first companion for Plaud Note Pro"
            copyright = "Copyright © 2026 Velum contributors"
            vendor = "Velum"
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icons/velum-512.png"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icons/velum.ico"))
            }
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icons/velum-512.png"))
                bundleID = "dev.velum.app"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Velum requires microphone access to record voice memos and meetings.</string>
                    """.trimIndent()
                }
                entitlementsFile.set(project.file("src/desktopMain/resources/macOS/entitlements.plist"))
            }
        }
    }
}


apply(from = rootProject.file("gradle/robolectric-offline.gradle.kts"))
