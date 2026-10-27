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
        maven { url = uri("https://jitpack.io") }
    }
}

include(":core")
include(":plaud-protocol")
include(":recorder")
include(":ai")
include(":ble")
include(":composeApp")
