import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    application
}

// The version lives in version.properties; release tags set it (see .github/workflows/release.yml).
version =
    Properties()
        .apply { file("version.properties").inputStream().use(::load) }
        .getProperty("version")
        ?.takeIf { it.isNotBlank() }
        ?: error("version.properties has no version")

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    implementation(libs.postgres)
    implementation(libs.mysql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dev.gantry.cli.MainKt"
    applicationName = "gantry"
    // A local tool next to the user's containers: cap the heap (the JVM otherwise allows 1/4 of RAM), use the
    // C1 JIT only (~90 MB less resident, a few ms per request) and hand freed heap back to the OS after spikes.
    applicationDefaultJvmArgs =
        listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Xmx512m",
            "-XX:TieredStopAtLevel=1",
            "-XX:G1PeriodicGCInterval=15000",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
        )
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

// The release archive install.sh and the Homebrew formula download: build/distributions/gantry-<version>.tar.gz.
tasks.distTar {
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

// The React dashboard is built with npm and bundled into the jar under /web.
// Pass -PskipWeb to build only the Kotlin side (the server then reports that the UI is missing).
val webDir = layout.projectDirectory.dir("web")

val webBuild =
    tasks.register<Exec>("webBuild") {
        workingDir = webDir.asFile
        commandLine("sh", "-c", "npm ci && npm run build")
        inputs.files(fileTree(webDir) { include("src/**", "public/**", "index.html", "package*.json", "vite.config.ts", "tsconfig.json") })
        outputs.dir(webDir.dir("dist"))
        onlyIf { !project.hasProperty("skipWeb") }
    }

tasks.processResources {
    dependsOn(webBuild)
    from(webDir.dir("dist")) {
        into("web")
        exclude(".gitkeep")
    }
}
