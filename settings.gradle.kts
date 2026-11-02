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
include(":composeApp")
