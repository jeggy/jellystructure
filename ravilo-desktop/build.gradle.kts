import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
val macFrameworks = listOf(
    "Security", "SystemConfiguration",                                  // R328 — the Keychain, the Computer Name
    "AVFoundation", "CoreMedia", "CoreVideo", "MediaPlayer", "IOKit", "AppKit", "QuartzCore", // R329 — the player
    "Network",                                                          // R330 — Bonjour
)
val buildMacNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libravilo-mac.dylib from native/*.swift with Xcode's swiftc (macOS hosts only)."
    onlyIf { isMacHost }
    inputs.files(swiftSources)
    outputs.file(macLibrary)
    val out = macLibrary.get().asFile
    doFirst { out.parentFile.mkdirs() }
    commandLine(
        listOf("xcrun", "swiftc", "-emit-library", "-O", "-whole-module-optimization",
            "-target", "arm64-apple-macos14.0", "-module-name", "RaviloMac", "-o", out.absolutePath) +
            swiftSources.files.sortedBy { it.name }.map { it.absolutePath } +
            macFrameworks.flatMap { listOf("-framework", it) },
    )
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildMacNative) }

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
            }
            linux {
                iconFile.set(project.file("icons/ravilo.png"))
            }
        }
    }
}
