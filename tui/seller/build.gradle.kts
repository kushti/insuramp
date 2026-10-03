plugins {
    kotlin("jvm") version "2.2.21"
    // Mosaic renders with the Compose runtime, so a module that declares
    // @Composable screens must carry the Compose compiler plugin too.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
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
    mainClass.set("p2pgate.tui.seller.MainKt")
}

dependencies {
    implementation(project(":tui:common"))
    implementation(project(":apps:core:dealprotocol"))

    // Mosaic: Compose-runtime terminal UI. 0.18.0 is the newest line that a
    // Kotlin 2.2.21 toolchain can consume (releases track Kotlin closely).
    implementation("com.jakewharton.mosaic:mosaic-runtime:0.18.0")
    // ZXing core: the meeting QR, rendered to the terminal. Same encoder the
    // backend uses for the PNG endpoint (`:backend` -> util/QrCodes.kt).
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Headless Mosaic harness — renders a screen without a TTY and replays keys.
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

// Cross-check against the web dashboard's endpoint list: every /v1 path the
// dashboard calls must exist on the client interface, or a seller feature is
// only reachable from the browser. Cheap to run, so it runs with the suite.
val checkDashboardCoverage by tasks.registering {
    val server = rootProject.file("backend/src/main/resources/dashboard/app.js")
    val client = rootProject.file("tui/common/src/main/kotlin/p2pgate/tui/common/KtorBackendClient.kt")
    doLast {
        // Every path the web dashboard calls must exist on the client, so a
        // seller feature cannot quietly live only in the browser. The dashboard
        // builds paths by concatenation ("/vaults/" + id + "/reclaim"), so the
        // comparison is on segment *shapes*: dynamic segments collapse to "{id}",
        // and a path the console reaches by any prefix of that shape counts.
        fun shape(path: String): String = path
            .replace(Regex("""\$\{?[A-Za-z]+\}?"""), "{id}")
            .replace(Regex("""\{(?!id)[A-Za-z]+\}"""), "{id}")
            .removeSuffix("/stream")
            .removeSuffix("/qr.png")
            .trimEnd('/')

        val used = Regex("""api\("(/[A-Za-z0-9{}/._$-]*)""").findAll(server.readText())
            .map { "/v1" + shape(it.groupValues[1]) }
            .toSet()

        val exposed = Regex("""/v1/[A-Za-z0-9{}/._$]*""").findAll(client.readText())
            .map { shape(it.value) }
            .toSet()

        val missing = used.filter { path ->
            exposed.none { it == path || it.startsWith("$path/") || path.startsWith("$it/") }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "the dashboard calls paths with no client counterpart: ${missing.sorted()}",
            )
        }
        // Sanity: a silently-empty scan would pass everything, so fail if the
        // dashboard stopped using api() (a refactor of its fetch helper).
        check(used.isNotEmpty()) { "no api() calls found in app.js — the coverage scan is broken" }
        logger.lifecycle("dashboard coverage: ${used.size} paths, all reachable from BackendClient")
    }
}
tasks.named("check") { dependsOn(checkDashboardCoverage) }
