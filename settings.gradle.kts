rootProject.name = "velum"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Robolectric's two instrumented android-all jars (~330 MB) come from Google's read-only Maven Central
        // mirror: repo.maven.apache.org answers 429 Too Many Requests to CI runners (composeApp robolectricAndroidAll).
        exclusiveContent {
            forRepository { maven("https://maven-central.storage-download.googleapis.com/maven2") }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
        // JitPack only for the two groups published there (no signing/ownership checks on JitPack).
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter {
                includeGroup("com.github.requery")
                includeGroup("com.github.k2-fsa.sherpa-onnx")
            }
        }
    }
}

include(":core")
include(":plaud-protocol")
include(":recorder")
include(":ai")
include(":ble")
include(":ble-testing")
include(":composeApp")
