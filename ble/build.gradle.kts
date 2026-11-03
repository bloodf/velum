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
    jvm()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":plaud-protocol"))
            implementation(project(":recorder"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":ble-testing"))
            }
        }

        val mobileMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kable.core)
            }
        }

        androidMain {
            dependsOn(mobileMain)
            dependencies {
                implementation(libs.java.websocket)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.java.websocket)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.junit)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.robolectric)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // With the default hierarchy, apple/ios intermediate sets exist after applyDefaultHierarchyTemplate.
        val iosMain by getting {
            dependsOn(mobileMain)
        }
    }
}

android {
    namespace = "dev.velum.ble"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// BleFakesNotInProductionTest and GattArchitectureTest scan other modules' sources and build files;
// declare them so the build cache cannot serve a stale result when only another module changed.
tasks.matching { it.name == "jvmTest" }.configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("*/src/*/**/*.kt", "*/src/*/**/*.java", "**/build.gradle.kts")
            exclude("**/build/**", "**/node_modules/**", "**/.gradle/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sourceGuardScanSources")
}

apply(from = rootProject.file("gradle/robolectric-offline.gradle.kts"))
