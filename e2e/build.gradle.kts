plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "org.p2pgate"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("p2pgate.e2e.MainKt")
}

dependencies {
    implementation(project(":apps:core:ergo"))
    implementation(project(":apps:core:dealprotocol"))
    implementation(project(":contracts"))
    implementation("org.ergoplatform:ergo-appkit_2.13:6.0.1")
    implementation("org.scorexfoundation:sigma-state_2.13:6.0.6")
    // secp256k1 key generation + the t/3407 Schnorr signer port
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

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
