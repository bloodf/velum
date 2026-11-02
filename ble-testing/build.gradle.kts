// Test doubles for :ble (BLE + Wi-Fi fakes). Consume only from test source sets (see BleFakesNotInProductionTest).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    // android kept so composeApp androidUnitTest can consume the fakes (#141); no iOS consumers.
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":ble"))
            api(project(":plaud-protocol"))
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.java.websocket)
        }
    }
}

android {
    namespace = "dev.velum.ble.testing"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
