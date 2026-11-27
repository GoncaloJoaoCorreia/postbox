rootProject.name = "postbox"

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

include("backend")
include("shared")
include("composeApp")
include("androidApp")
