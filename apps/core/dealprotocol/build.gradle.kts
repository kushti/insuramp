plugins {
    kotlin("jvm") version "2.0.21"
}

group = "org.p2pgate"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(kotlin("test"))
    // Reference BLAKE2b-256 oracle for the property test only; main code has
    // zero implementation dependencies (pure-Kotlin Blake2b256 is the impl).
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
    }
}
