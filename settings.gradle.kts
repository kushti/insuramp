pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "p2pgate"

include("contracts")
include("apps:core:dealprotocol")
include("apps:core:ergo")
include("e2e")
include("backend")
