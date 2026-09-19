plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "org.p2pgate"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "p2pgate.backend.api.ServerKt"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

// Ktor 3.0.3 is the newest 3.x line built against Kotlin 2.0.21 (its
// kotlin-stdlib dependency is exactly 2.0.21), so the whole Ktor surface is
// metadata-compatible with this module's compiler.
val ktorVersion = "3.0.3"

dependencies {
    implementation(project(":apps:core:dealprotocol"))
    implementation(project(":apps:core:ergo"))
    // :apps:core:ergo keeps :contracts as an `implementation` dep, so its
    // ContractParams is re-declared here (used for RECLAIM_TIMEOUT_BLOCKS).
    implementation(project(":contracts"))

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // The chain stack re-declared: :apps:core:ergo keeps appkit/sigma as
    // `implementation`, so they do not leak onto this module's compile
    // classpath transitively — yet VaultManager deals in SignedTransaction /
    // ErgoTree surface types from those libraries.
    implementation("org.ergoplatform:ergo-appkit_2.13:6.0.1")
    implementation("org.scorexfoundation:sigma-state_2.13:6.0.6")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Seller-meeting QR rendering (specs/seller-dashboard.md §4): `core` only —
    // the PNG raster is done via javax.imageio (see backend/util/QrCodes.kt).
    implementation("com.google.zxing:core:3.5.3")

    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
    }
}
