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
    // The plugin's own convention is false: a gui-header launcher then CreateProcess()es
    // javaw and returns within about a second, while the game is still starting up. cmd's
    // `start /wait` in tools/booth/start-booth.bat is satisfied against THAT return, not
    // against the game exiting - so the watchdog would relaunch a new game every few
    // seconds, forever, starting on the first boot at the venue. BoothLauncherTest asserts
    // this is set.
    stayAlive       = true
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

////////////////////////////////////////////////////////////////////////////////// macOS release

// The Windows path above ships an `.exe` with a bundled Windows JRE, and `tasks.jar` excludes the
// macOS and Linux natives from it (line 81) - so that artefact could never run on a Mac even in
// principle. The macOS equivalent is a `jpackage` app-image: a self-contained `One More Breath.app`
// carrying its own runtime. It exists for demoing the booth build off a laptop, not for the cabinet.
//
// Kotlin block comments NEST, so a `/` `*` sequence inside one silently opens a second level and
// the closing `*` `/` only gets you back to the first - the remainder of the file is then comment
// and every task in it just disappears with no error at all. That is why this paragraph is line
// comments: writing a glob like the one on line 81 inside a block comment here did exactly that,
// and `./gradlew buildMacRelease` answered "Task not found" against a file that plainly declared it.

val macAppName = "One More Breath"
val macReleaseDir = layout.projectDirectory.dir("release/macos")
val macAppDir = macReleaseDir.dir("$macAppName.app")
val javaToolchains = extensions.getByType<JavaToolchainService>()

/**
 * **`jpackage` MUST come from the Gradle TOOLCHAIN, never from `PATH` or `JAVA_HOME`.**
 *
 * `jpackage --type app-image` bundles the runtime of *the JDK it itself belongs to*, not the one
 * the classes were compiled against. This project compiles at `jvmToolchain(23)` (line 33) while
 * the development machine's system JDK is 19, so `/usr/bin/jpackage` produces a bundle that pairs
 * Java 23 class files with a Java 19 runtime. That combination builds and installs without a
 * single warning and then dies the moment a player double-clicks it:
 *
 *     java.lang.UnsupportedClassVersionError: EnPustTilKt has been compiled by a more recent
 *     version of the Java Runtime (class file version 67.0), this version of the Java Runtime
 *     only recognizes class file versions up to 63.0
 *
 * That error is close to undiagnosable in the field: it happens inside a packaged `.app`, whose
 * stdout goes nowhere a double-click can see, so the symptom presented to whoever is holding the
 * laptop is "the icon bounces once and nothing happens". It was hit for real while adding this
 * task. Resolving through `javaToolchains` is what makes the runtime that gets bundled the *same*
 * runtime the bytecode targets, by construction, on any machine and whatever is on `PATH`.
 *
 * Resolved lazily (`map`, not `get()`) so that merely configuring the build cannot trigger a
 * toolchain download for someone who never runs a macOS release.
 */
val macJpackage = javaToolchains
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(23)) }
    .map { it.metadata.installationPath.file("bin/jpackage").asFile.absolutePath }

/**
 * Code signing is wired but INERT by default: with no `-PmacSigningIdentity` the signing flags are
 * not passed at all, so a clean clone with no Apple account still builds a working bundle. Pass
 * `-PmacSigningIdentity="Developer ID Application: Name (TEAMID)"` to sign. Signing alone is not
 * distribution - see the macOS subsection of CLAUDE.md's "Config and release" for why a `.dmg`
 * still will not open on someone else's Mac without notarization.
 */
val macSigningArgs: List<String> = (findProperty("macSigningIdentity") as String?)
    ?.takeIf { it.isNotBlank() }
    ?.let { listOf("--mac-sign", "--mac-signing-key-user-name", it) }
    ?: emptyList()

fun Task.requireMacOs()
{
    if (!org.gradle.internal.os.OperatingSystem.current().isMacOsX)
        throw GradleException("$name builds a macOS app bundle and only runs on macOS.")
}

tasks.register<Exec>("buildMacRelease") {
    group = "release"
    description = "Builds a self-contained release/macos/$macAppName.app via jpackage."
    // `installDist` lays out build/install/en-pust-til/lib, which is what jpackage is pointed at.
    // It is built from `tasks.jar`, which already excludes `*-dev*`, so the bundle ships the
    // fullscreen booth `application.cfg` and NOT `application-dev.cfg`. Verified on a launched
    // bundle: after "Initializing configuration" the log carries 0 INFO and 0 DEBUG lines and the
    // window comes up 1920x1200 at (0,0) on window layer 25, i.e. `logLevel = WARN` + FULLSCREEN.
    //
    // `installDist` is also the reason the bundle can run at all, and this is NOT obvious. Line 81
    // excludes `macos/**`, and LWJGL's macOS natives really do sit at top level in
    // pulse-engine-0.13.0.jar (`macos/arm64/org/lwjgl/...`), so the fat jar has 0 `.dylib` in it -
    // 12 `.dll` and nothing else. What restores them is that `installDist` copies the ORIGINAL
    // dependency jars into lib/ beside the fat one, and jpackage puts all four on app.classpath,
    // so the unstripped pulse-engine jar supplies the dylibs. Package the fat jar ALONE and the
    // .app dies in GLFW init with no native library at all.
    dependsOn(tasks.installDist)

    // NOTE `project.name`, not `name`: inside a task configuration block the implicit receiver is
    // the Task, whose `name` is "buildMacRelease".
    val libDir = layout.buildDirectory.dir("install/${project.name}/lib")
    outputs.dir(macAppDir)
    // A release artefact must never be silently skipped: the whole point of running this task is
    // to hold the bundle that is about to be handed to someone. The delete below is what makes an
    // unconditional re-run safe.
    outputs.upToDateWhen { false }

    doFirst {
        requireMacOs()
        // jpackage REFUSES to write into an existing app-image ("Error: ... already exists") and
        // exits non-zero, so without this the task works exactly once. Deleting rather than
        // overwriting also means a file dropped from the build can never survive inside a bundle.
        delete(macAppDir)

        executable = macJpackage.get()
        args(
            "--type", "app-image",
            "--name", macAppName,
            "--app-version", project.version.toString(),
            "--input", libDir.get().asFile.absolutePath,
            "--main-jar", "$releaseName.jar",
            "--main-class", mainClass,
            // GLFW must own the process's first thread on macOS or window creation crashes the
            // JVM. The `application {}` block above only covers `./gradlew run`; a packaged app
            // has no Gradle around it, so the flag has to be baked into the bundle's own config.
            "--java-options", "-XstartOnFirstThread",
            "--dest", macReleaseDir.asFile.absolutePath
        )
        args(macSigningArgs)
    }
}

tasks.register<Exec>("buildMacInstaller") {
    group = "release"
    description = "Wraps the built .app into release/macos/$macAppName-$version.dmg."
    dependsOn("buildMacRelease")

    val dmg = macReleaseDir.file("$macAppName-${project.version}.dmg")
    outputs.file(dmg)
    outputs.upToDateWhen { false }

    doFirst {
        requireMacOs()
        // `--app-image` packages the bundle buildMacRelease just produced instead of building a
        // second one from the jars. Two jpackage runs over the same input are not guaranteed to
        // produce identical bundles (timestamps, signature), and the .app is the artefact that
        // was actually launched and smoke-tested - the .dmg should contain THAT one.
        delete(dmg)

        executable = macJpackage.get()
        args(
            "--type", "dmg",
            "--name", macAppName,
            "--app-version", project.version.toString(),
            "--app-image", macAppDir.asFile.absolutePath,
            "--dest", macReleaseDir.asFile.absolutePath
        )
        args(macSigningArgs)
    }
}
