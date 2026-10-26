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
    }
}

include(":core")
include(":plaud-protocol")
include(":ai")
include(":ble")
include(":composeApp")
