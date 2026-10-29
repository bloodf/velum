import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.roborazzi)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
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
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":ai"))
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
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.junit)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.robolectric)
                implementation(libs.roborazzi)
                implementation(libs.roborazzi.compose)
                implementation(libs.roborazzi.rule)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidInstrumentedTest by getting {
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
            }
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        val desktopTest by getting {
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
        versionCode = 100
        versionName = "1.0.0"
        testInstrumentationRunner = "dev.velum.app.devicewalk.DeviceWalkTestRunner"
        // Prefer DSL over -Pandroid.testInstrumentationRunnerArguments.* (breaks config cache).
        testInstrumentationRunnerArguments += mapOf(
            "velumDeviceWalk" to "true",
            // Device walk + AndroidKeyStore SecureStore round-trip (#92). Comma-separated
            // exact class list only — no package filter.
            "class" to
                "dev.velum.app.devicewalk.DeviceWalkUiTest," +
                    "dev.velum.app.security.AndroidSecureStoreKeystoreInstrumentedTest",
        )
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
        val uploadStorePath = System.getenv("VELUM_UPLOAD_STORE_FILE")
        if (!uploadStorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(uploadStorePath)
                storePassword = System.getenv("VELUM_UPLOAD_STORE_PASSWORD")
                    ?: error("VELUM_UPLOAD_STORE_PASSWORD required when store file is set")
                keyAlias = System.getenv("VELUM_UPLOAD_KEY_ALIAS")
                    ?: error("VELUM_UPLOAD_KEY_ALIAS required when store file is set")
                keyPassword = System.getenv("VELUM_UPLOAD_KEY_PASSWORD")
                    ?: error("VELUM_UPLOAD_KEY_PASSWORD required when store file is set")
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
            // Prefer upload key when present; otherwise debug keystore so local
            // assembleRelease still installs without the upload secret (#90/#106).
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
            }
        }
    }
}

