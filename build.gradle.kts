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

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("macos/**", "linux/**") // Exclude natives for Mac and Linux when creating Windows exe
    exclude("*-dev*") // Exclude dev config and scripts
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
    destinationDirectory.set(file("release/win64"))
    archiveFileName.set("${releaseName}.zip")
}