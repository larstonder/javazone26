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
    //
    // THE REST OF THIS LIST HAD NEVER RUN ON A MAC. -XX:+UseZGC, the heap sizes and
    // -XX:+DisableExplicitGC were all set in the launch4j block, which builds the WINDOWS exe
    // only — so the tuned GC configuration applied to no machine anybody was playing on once
    // the target was corrected to "a Mac with a controller".
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX)
        applicationDefaultJvmArgs = listOf(
            "-XstartOnFirstThread",

            // Equal min and max: the heap never grows or shrinks, so no resize pause can land
            // inside a frame. 512 MB is ~10x the MEASURED 49 MB peak live set — this game
            // allocates about 1 MB/s and holds 10-30 MB.
            "-Xms512m", "-Xmx512m",

            // Pre-fault all 512 MB once at startup so no page fault happens mid-frame. Only
            // cheap BECAUSE the heap is small; do not pair this with a multi-gigabyte -Xms.
            "-XX:+AlwaysPreTouch",

            // Measured win: removes two System.gc() full pauses (10.0 ms and 12.8 ms) that
            // fire during engine init. PulseEngineImpl.postGameInit calls System.gc() and the
            // engine expects that collection to happen — it does not need to.
            "-XX:+DisableExplicitGC"
        )
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

/**
 * Compiles `tools/macpad/MacPadBridge.swift` into `build/macpad/macpadbridge` — the helper that
 * reads `GameController.framework` for the macOS input bridge (`render/MacPadBridge.kt`).
 *
 * macOS ONLY, AND NON-FATAL BY DESIGN. It is wired into `run` so a dev build picks it up, but a
 * machine with no Swift toolchain must still be able to run the game: `MacPadHelper.locate()`
 * simply finds nothing and `EnPustTil.buildGamepadStateReader` falls back to the GLFW read that
 * shipped before this existed. Windows never registers the task at all, so the `.exe` build is
 * untouched by construction.
 */
val macPadBinary = layout.buildDirectory.file("macpad/macpadbridge")

tasks.register<Exec>("buildMacPadBridge") {
    group = "build"
    description = "Compiles the macOS GameController helper into build/macpad/macpadbridge."
    // `onlyIf` rather than a conditional `register`: the task still EXISTS on Windows (so
    // `dependsOn` below resolves and the build script stays one shape), it simply does nothing.
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isMacOsX }
    val source = layout.projectDirectory.file("tools/macpad/MacPadBridge.swift")
    inputs.file(source)
    outputs.file(macPadBinary)
    doFirst { macPadBinary.get().asFile.parentFile.mkdirs() }
    commandLine(
        "swiftc", "-O",
        "-o", macPadBinary.get().asFile.absolutePath,
        source.asFile.absolutePath
    )
}

tasks.named("run") { dependsOn("buildMacPadBridge") }

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
    // The macOS GameController helper ships INSIDE the bundle - see the doLast below, and
    // `render/MacPadBridge.kt` for why the game needs a second input path on macOS at all.
    dependsOn("buildMacPadBridge")

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
            //
            // The heap/GC flags below mirror the `application {}` block above for the same
            // measured reasons (10x the peak live set, pre-touch to avoid a mid-frame page
            // fault, drop the two System.gc() boot pauses). `-Dorg.lwjgl.util.NoChecks=true` is
            // added ONLY here and never on `./gradlew run`: it disables LWJGL's per-call
            // parameter validation on every GL entry point, a real per-frame saving, but it
            // means a mistake that would otherwise throw instead corrupts state silently — a
            // trade only acceptable in a build nobody is going to iterate against.
            "--java-options", "-XstartOnFirstThread",
            "--java-options", "-Xms512m",
            "--java-options", "-Xmx512m",
            "--java-options", "-XX:+AlwaysPreTouch",
            "--java-options", "-XX:+DisableExplicitGC",
            "--java-options", "-Dorg.lwjgl.util.NoChecks=true",
            "--dest", macReleaseDir.asFile.absolutePath
        )
        args(macSigningArgs)
    }

    /**
     * Drops `macpadbridge` into the bundle's own `Contents/MacOS`, which is where
     * `MacPadHelper.candidatePaths` looks first (`java.home` inside a jpackage app-image is
     * `<app>.app/Contents/runtime/Contents/Home`, so the two are four levels apart - asserted by
     * `MacPadHelperTest`).
     *
     * AFTER jpackage, not before, because jpackage refuses to write into an existing app-image
     * and builds `Contents/` itself. The consequence is that when signing is enabled the bundle
     * has already been signed by the time the helper lands, so the helper is signed on its own
     * and the bundle is then re-signed - adding a file to a signed bundle invalidates its
     * signature, and an invalid signature is worse than none: Gatekeeper reports it as damaged
     * rather than merely unsigned.
     *
     * A MISSING HELPER IS NOT A BUILD FAILURE. `MacPadHelper.locate()` finding nothing degrades
     * to the GLFW read that shipped before this existed, so a machine without a Swift toolchain
     * still produces a working bundle - it just produces one where a Switch Pro Controller does
     * not work, which is exactly where this project was before.
     */
    doLast {
        val helper = macPadBinary.get().asFile
        if (!helper.exists())
        {
            logger.warn("buildMacRelease: ${'$'}helper was not built - the bundle will fall back to the GLFW gamepad read.")
            return@doLast
        }
        val target = macAppDir.file("Contents/MacOS/${'$'}{helper.name}").asFile
        helper.copyTo(target, overwrite = true)
        target.setExecutable(true)

        val identity = (findProperty("macSigningIdentity") as String?)?.takeIf { it.isNotBlank() }
        if (identity != null)
        {
            exec { commandLine("codesign", "--force", "--sign", identity, target.absolutePath) }
            exec { commandLine("codesign", "--force", "--deep", "--sign", identity, macAppDir.asFile.absolutePath) }
        }
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
