plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

group = "org.p2pgate"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("p2pgate.tui.buyer.MainKt")
}

dependencies {
    implementation(project(":tui:common"))
    implementation(project(":apps:core:dealprotocol"))
    // The chain layer: builders, verifiers and ChainSource — the buyer console
    // builds and broadcasts its own claim transactions rather than only
    // printing instructions (the Android app still does the latter).
    implementation(project(":apps:core:ergo"))
    implementation(project(":contracts"))

    implementation("com.jakewharton.mosaic:mosaic-runtime:0.18.0")
    // ZXing core: rendering a deal's QR to the terminal, same encoder the
    // backend uses for its PNG endpoint (`:backend` -> util/QrCodes.kt).
    implementation("com.google.zxing:core:3.5.3")
    // The encrypted key file's plaintext payload is kotlinx-serialised JSON.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // `:apps:core:ergo` declares appkit as `implementation`, so the console
    // needs its own handle on the appkit types (NetworkType, SignedTransaction,
    // ColdErgoClient) to sign a claim.
    implementation("org.ergoplatform:ergo-appkit_2.13:6.0.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.jakewharton.mosaic:mosaic-testing:0.18.0")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        showExceptions = true
    }
}
