plugins {
    kotlin("jvm") version "2.2.20"
    id("edu.sc.seis.launch4j") version "4.0.0"
    application
}

application {
    mainClass.set("EnPustTilKt")
    // LWJGL/GLFW requires the window to be created on the process's first thread.
    // The Gradle `run` task launches the JVM on a worker thread by default, so on
    // macOS this must be forced explicitly. No-op on other platforms; the Windows
    // release build below uses launch4j's own jvmOptions instead.
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX)
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
}

group = "org.example"
version = "1.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.repsy.io/mvn/njoh/public")
    }
}

dependencies {
    implementation("no.njoh:pulse-engine:0.13.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}

////////////////////////////////////////////////////////////////////////////////// Windows release

val releaseName = "$name-$version"
val releaseBuildDir = "$buildDir/$releaseName"
val mainClass = "EnPustTilKt"

/**
 * `src/main/resources/pulseengine/shaders/renderers/{quad,line}.vert` shadow two engine shader
 * files on the classpath to repair `Surface.drawQuad`/`drawLine`, which render nothing at all on
 * macOS (read those files' headers for the mechanism). Those overrides are for the DEVELOPMENT
 * classpath only — `./gradlew run`, `installDist`, and the engine's scene editor. The Windows
 * release jar deliberately keeps the ENGINE's copies:
 *
 *  - The booth `.exe` must stay bit-for-bit the rendering behaviour that was playtested. The game
 *    draws every rectangle through `Surface.fillRect` (i.e. `drawTexture`) and never calls
 *    `drawQuad`, so shipping the override buys a player exactly nothing.
 *  - The bug is macOS-only with ~85% confidence and is UNVERIFIED on Windows — there is no Windows
 *    machine here to check it on. Shipping a shader we cannot test on the target platform, weeks
 *    before the conference, to fix a bug no player can reach, is a bad trade.
 *
 * That was already the outcome, but only by accident: with `duplicatesStrategy = INCLUDE` both
 * copies are written into the fat jar and the classloader happens to expose the engine's. That is
 * merge order, not a decision — reordering the `from(...)` lines below would silently flip it, in
 * the one artefact nobody re-checks. So the exclusion is made explicit here instead.
 *
 * It drops our copies by their SOURCE DIRECTORY rather than by position in the merge, so it cannot
 * be inverted by ordering; and it is deliberately not `exclude("pulseengine/shaders/...")`, because
 * a task-level `exclude` applies to the whole copy spec and would drop the engine's copies too,
 * leaving the release with no quad/line shader at all.
 *
 * If we ever do get a Windows machine and verify the fix there, delete this block — the overrides
 * then simply ship. See docs/superpowers/reports/2026-08-06-drawquad-macos-investigation.md §4.1.
 */
val devOnlyShaderOverrides = setOf(
    "pulseengine/shaders/renderers/quad.vert",
    "pulseengine/shaders/renderers/line.vert"
)

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("macos/**", "linux/**") // Exclude natives for Mac and Linux when creating Windows exe
    exclude("*-dev*") // Exclude dev config and scripts

    val ourResourcesDir = sourceSets.main.get().output.resourcesDir!!.absolutePath
    eachFile {
        // `file` is only touched for the two candidate paths — for a zipTree entry it forces an
        // extraction, so it must not run for all ~10k files in the merged tree.
        if (path in devOnlyShaderOverrides && file.absolutePath.startsWith(ourResourcesDir))
            exclude()
    }

    manifest { attributes("Main-Class" to mainClass) }
}

launch4j {
    bundledJrePath  = "jre"
    mainClassName   = mainClass
    outputDir       = releaseBuildDir
    initialHeapSize = 1024
    maxHeapSize     = 4096
    jvmOptions      = listOf(
        "-XX:+UseZGC",            // Use Z Garbage Collector for low latency
        "-XX:SoftMaxHeapSize=2g", // 2GB target heap size to limit GC impact
        "-XX:+DisableExplicitGC"  // Ignore System.gc() from libraries to prevent sudden stutter / frame drops
    )
}

tasks.register<Zip>("buildWin64Release") {
    group = "release"
    dependsOn("createExe")
    doFirst {
        delete("$releaseBuildDir/lib")
        copy {
            from(zipTree("jre/minimal-jre23-win64.zip"))
            into("$releaseBuildDir/jre")
        }
    }
    from(releaseBuildDir)
    // The booth is started by this, not by the exe - see the script's own header, and
    // BoothLauncherTest, which fails the build if this line is ever dropped.
    from(file("tools/booth/start-booth.bat"))
    destinationDirectory.set(file("release/win64"))
    archiveFileName.set("${releaseName}.zip")
}
