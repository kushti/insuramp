plugins {
    id("com.android.application") version "8.6.1"
    kotlin("android") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    // KSP build matching Kotlin 2.0.21 (Room's annotation processor).
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

android {
    namespace = "p2pgate.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "p2pgate.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        // Debug builds point at the local demo backend via `adb reverse
        // tcp:8080 tcp:8080`: the emulator's own loopback forwards to the
        // host. Release keeps the placeholder default (real deployments set
        // the backend URL via the backend_url pref).
        debug {
            buildConfigField("String", "BACKEND_URL", "\"http://127.0.0.1:8080\"")
        }
    }

    compileOptions {
        // java.time is native from API 26, so no desugaring is needed even
        // though :apps:core:dealprotocol uses java.time throughout.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // JVM unit tests run on JUnit 5 like the rest of the repo.
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Ktor 3.0.3 matches the operator backend (backend/build.gradle.kts) — the
// whole Ktor surface stays on one metadata-compatible version.
val ktorVersion = "3.0.3"

dependencies {
    implementation(project(":apps:core:dealprotocol"))

    // Compose (BOM covers the androidx.compose.* artifacts).
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Per-app locale switching (AppCompatDelegate.setApplicationLocales —
    // persists across process death on all API levels).
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Network: Ktor client (CIO), WebSockets, kotlinx JSON.
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Local deal store (Room via KSP) + background polling.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // QR scanning: ZXing (pure, offline, camera-based — the §8.6 privacy
    // default; ML Kit was rejected). Same BC major line as the JVM modules.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Seller-location map on the quote screen: osmdroid (pure OSM tiles, no
    // Google Play Services, no API key — same privacy default as ZXing).
    // No location permission is requested: the map shows sellers, never the
    // buyer's position.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed")
    }
}
