pluginManagement {
    repositories {
        // AGP (com.android.application) resolves from google(); the Kotlin
        // plugins and KSP come from the portal / central.
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "p2pgate"

include("contracts")
include("apps:core:dealprotocol")
include("apps:core:ergo")
include("e2e")
include("backend")

// The buyer app lives in apps/app (specs/android-app.md §2.1) but keeps the
// short Gradle path :app — every build doc and the M4 gate invoke :app:*.
include(":app")
project(":app").projectDir = file("apps/app")
