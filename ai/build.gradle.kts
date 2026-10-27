plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // litertlm-android ships Kotlin 2.4 metadata; keep compiling on 2.2.x toolchain.
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(project(":ble"))
                implementation(project(":plaud-protocol"))
            }
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sherpa.onnx.android)
            implementation(libs.kopus)
            implementation(libs.commons.compress)
            implementation(libs.litertlm.android)
            implementation(libs.androidx.work.runtime)
            implementation("androidx.core:core-ktx:1.15.0")
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.kopus)
            implementation(libs.commons.compress)
        }
    }
}

android {
    namespace = "dev.velum.ai"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            // Needle/Whistle JNI (libvelum_needle) + libc++_shared; x86 emulators use Whisper Tiny.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    sourceSets.getByName("main").jniLibs.srcDir("src/androidMain/jniLibs")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            // Ship Needle + libc++_shared from this module into consuming APKs.
            keepDebugSymbols += listOf("**/libvelum_needle.so")
        }
    }
}
