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
    implementation("org.ergoplatform:ergo-appkit_2.13:6.0.1")
    implementation("org.scorexfoundation:sigma-state_2.13:6.0.6")

    testImplementation("org.scorexfoundation:sigma-state_2.13:6.0.6:tests")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // opt-in spend-rejection diagnostics: ./gradlew test -Dp2pgate.debug=1
    System.getProperty("p2pgate.debug")?.let { systemProperty("p2pgate.debug", it) }
    testLogging {
        events("passed", "failed")
        showExceptions = true
    }
}

// ErgoScript sources ship on the classpath as resources so ContractCompiler can
// load them by name from both main and test code.
sourceSets {
    main {
        resources {
            srcDir("src/main/ergoscript")
        }
    }
}
