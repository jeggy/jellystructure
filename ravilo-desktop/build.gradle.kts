import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// R328 — Ravilo on the Mac. The whole UI is ravilo-ui's (the TV layout, driven by a pointer and a keyboard);
// this module is the window, the menu bar, the process and the packaging. R331 turns `createDistributable`'s
// output into the signed .dmg on every GitHub release.
kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(projects.raviloUi)
                implementation(projects.raviloI18n)
                implementation(projects.shared)
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.compose.material3)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// ─── FR-R328-4 — the Swift library ────────────────────────────────────────────────────────────────
// `native/*.swift` → `libravilo-mac.dylib`, arm64, macOS 14 (D2). Only a Mac with Xcode's command-line tools can
// build it, so on any other host the task is skipped and the app runs without it (MacNative's fallbacks). It lands
// in Compose's app resources (`macos-arm64/`), so `run` and the packaged app both find it through
// `compose.application.resources.dir`.
val isMacHost = System.getProperty("os.name").lowercase().startsWith("mac")
val nativeResources = layout.buildDirectory.dir("native-resources")
val macLibrary = nativeResources.map { it.file("macos-arm64/libravilo-mac.dylib") }
val swiftSources = fileTree("native") { include("**/*.swift") }

// ─── R386 (FR-R386-1) — Sparkle 2, pinned ─────────────────────────────────────────────────────────
// The release tarball is fetched once into build/sparkle, its SHA-256 checked against the pin below, and unpacked:
// `Sparkle.framework` (linked weakly by the Swift library and copied into Ravilo.app/Contents/Frameworks) and `bin/`
// (`sign_update` and `generate_keys`, used by deploy-macos.yml and the key ceremony in README.md). Bumping Sparkle
// is a deliberate change of both lines.
val sparkleVersion = "2.10.0"
val sparkleSha256 = "c2bf58aa8387266ac179357b1415d6f2635f044da8be41042af32425dae6da0c"
val sparkleDir = layout.buildDirectory.dir("sparkle/Sparkle-$sparkleVersion")
val fetchSparkle by tasks.registering {
    group = "build"
    description = "Downloads the pinned Sparkle $sparkleVersion release and unpacks Sparkle.framework and bin/ (macOS hosts only)."
    onlyIf { isMacHost }
    inputs.property("sparkleSha256", sparkleSha256)
    outputs.dir(sparkleDir)
    doLast {
        val dir = sparkleDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val archive = File(dir.parentFile, "Sparkle-$sparkleVersion.tar.xz")
        URI("https://github.com/sparkle-project/Sparkle/releases/download/$sparkleVersion/Sparkle-$sparkleVersion.tar.xz")
            .toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == sparkleSha256) { "Sparkle-$sparkleVersion.tar.xz is $digest, the pin says $sparkleSha256 — refusing to use it." }
        val untar = ProcessBuilder("tar", "-xJf", archive.absolutePath, "-C", dir.absolutePath).inheritIO().start()
        check(untar.waitFor() == 0) { "could not unpack ${archive.name}" }
        check(File(dir, "Sparkle.framework").isDirectory && File(dir, "bin/sign_update").isFile) { "Sparkle's archive has no Sparkle.framework or bin/sign_update" }
    }
}

val macFrameworks = listOf(
    "Security", "SystemConfiguration",                                  // R328 — the Keychain, the Computer Name
    "AVFoundation", "CoreMedia", "CoreVideo", "MediaPlayer", "IOKit", "AppKit", "QuartzCore", // R329 — the player
    "Network",                                                          // R330 — Bonjour
)
val buildMacNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libravilo-mac.dylib from native/*.swift with Xcode's swiftc (macOS hosts only)."
    onlyIf { isMacHost }
    dependsOn(fetchSparkle)
    inputs.files(swiftSources)
    outputs.file(macLibrary)
    val out = macLibrary.get().asFile
    val sparkle = sparkleDir.get().asFile
    doFirst { out.parentFile.mkdirs() }
    commandLine(
        listOf("xcrun", "swiftc", "-emit-library", "-O", "-whole-module-optimization",
            "-target", "arm64-apple-macos14.0", "-module-name", "RaviloMac", "-o", out.absolutePath) +
            swiftSources.files.sortedBy { it.name }.map { it.absolutePath } +
            macFrameworks.flatMap { listOf("-framework", it) } +
            // R386 — Sparkle is linked weakly: a bundle without it still loads this library (Update.swift says
            // `unavailable`) instead of losing playback and the Keychain. The rpaths find it in the packaged app
            // (Contents/Frameworks) and, for `gradle run`, in the pinned download.
            listOf("-F", sparkle.absolutePath, "-Xlinker", "-weak_framework", "-Xlinker", "Sparkle",
                "-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks",
                "-Xlinker", "-rpath", "-Xlinker", "@loader_path/../../Frameworks",
                "-Xlinker", "-rpath", "-Xlinker", sparkle.absolutePath),
    )
}
// R342 (FR-R342-2) — the music Dock icon's pictures (scripts/render-brand-icons.sh dock) travel beside the Swift
// library that sets them, so only the Mac's package carries them; a `gradle run` without them keeps the installed icon.
val copyDockPictures by tasks.registering(Copy::class) {
    from("icons/dock") { include("*.png") }
    into(nativeResources.map { it.dir("macos-arm64/dock") })
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildMacNative, copyDockPictures) }

// R386 (FR-R386-1) — Sparkle.framework into Ravilo.app/Contents/Frameworks, straight after the app image is made.
// `ditto` rather than a Gradle Copy: a framework is a tree of symlinks (Versions/Current, Resources, …) that a
// Copy would flatten and so break its signature. deploy-macos.yml signs it, inside-out, before the bundle.
val bundleSparkle by tasks.registering(Exec::class) {
    group = "build"
    description = "Copies Sparkle.framework into the packaged Ravilo.app's Contents/Frameworks (macOS hosts only)."
    onlyIf { isMacHost }
    dependsOn(fetchSparkle)
    val app = layout.buildDirectory.dir("compose/binaries/main/app/Ravilo.app")
    val sparkle = sparkleDir
    doFirst {
        val frameworks = File(app.get().asFile, "Contents/Frameworks")
        frameworks.mkdirs()
        File(frameworks, "Sparkle.framework").deleteRecursively()
    }
    commandLine("ditto", sparkle.get().asFile.resolve("Sparkle.framework").absolutePath,
        app.get().asFile.resolve("Contents/Frameworks/Sparkle.framework").absolutePath)
}
tasks.matching { it.name == "createDistributable" }.configureEach { finalizedBy(bundleSparkle) }

// R386 (FR-R386-2) — the public half of the update key pair: one line, committed. Absent until the key is made
// (README.md, "The update key"); a bundle without it has no `SUPublicEDKey` and Sparkle reports itself unavailable.
val sparklePublicKey: String? = file("sparkle/SUPublicEDKey.txt").takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
// A `workflow_dispatch` spike build (R386 handoff) is made with `-Pravilo.updateFeedOverride=true`: it honours the
// debug feed override and may read a feed on the local network. A release build is never made with it.
val updateFeedOverride = (findProperty("ravilo.updateFeedOverride") as String?)?.trim() == "true"

// R331 (FR-R331-1, dev review 4) — jpackage wants N.N.N with a major ≥ 1: the release workflow passes the tag's
// MAJOR.MINOR as `-Pravilo.macPackageVersion=MAJOR.MINOR.0`; a local build derives it the same way or says 1.0.0.
val macPackageVersion: String = (findProperty("ravilo.macPackageVersion") as String?)?.trim()?.ifBlank { null }
    ?: (rootProject.extra["buildVersion"] as String).let { v ->
        Regex("""^(\d+)\.(\d+)$""").matchEntire(v)?.let { "${it.groupValues[1]}.${it.groupValues[2]}.0" }
    }
    ?: "1.0.0"

compose.desktop {
    application {
        mainClass = "dev.jellystructure.ravilo.desktop.MainKt"
        // FR-R328-5 (dev review 7) — the menu bar in macOS's own bar, and the app's name in it for `gradle run`.
        jvmArgs += listOf("-Dapple.laf.useScreenMenuBar=true", "-Dapple.awt.application.name=Ravilo")
        // R383 (FR-R383-4, DEB-659) — an explicit ceiling; the default was a quarter of the machine's RAM (8 GB on a 32 GB Mac).
        // The Mac measurement (DEB-1466) put the live Java heap at 60–100 MB once the Songs list is lazy, so 1 GB leaves
        // ten times that, and Coil's memory cache (20 % of the max heap) is bounded at ~200 MB. 4K video memory is native
        // (AVFoundation/IOSurface), outside this limit. Shared with the Linux build.
        jvmArgs += "-Xmx1g"
        if (updateFeedOverride) jvmArgs += "-Dravilo.update.feed.allowed=true"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Ravilo"
            packageVersion = macPackageVersion
            description = "Ravilo — films, series and music from your jellystructure server"
            vendor = "jellystructure"
            appResourcesRootDir.set(nativeResources)
            // jlink's runtime: what the app, Ktor/OkHttp (TLS, incl. the Chromecast's), Compose and JNA reach for.
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.prefs", "java.sql",
                "jdk.crypto.ec", "jdk.unsupported", "jdk.accessibility")
            macOS {
                bundleID = "dev.jellystructure.ravilo"
                dockName = "Ravilo"
                minimumSystemVersion = "14.0"   // R328 D2 / R331 FR-R331-2
                appCategory = "public.app-category.entertainment"
                iconFile.set(project.file("icons/ravilo.icns"))
                // R330 (FR-R330-8) — without these macOS 15 blocks Bonjour silently; the first scan asks the viewer.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>Ravilo looks for your speakers and TVs on this network.</string>
                        <key>NSBonjourServices</key>
                        <array><string>_googlecast._tcp</string></array>
                        <key>SUEnableAutomaticChecks</key>
                        <false/>
                    """.trimIndent() +
                        // R386 (FR-R386-1) — Sparkle verifies every update against this key. There is deliberately no
                        // SUFeedURL: the feed is set per check (FR-R386-3).
                        (sparklePublicKey?.let { "\n<key>SUPublicEDKey</key>\n<string>$it</string>" } ?: "") +
                        // A spike build may be pointed at an appcast on this Mac or its network, over plain http.
                        (if (updateFeedOverride) "\n<key>NSAppTransportSecurity</key>\n<dict><key>NSAllowsLocalNetworking</key><true/></dict>" else "")
                }
            }
            linux {
                iconFile.set(project.file("icons/ravilo.png"))
            }
        }
    }
}
