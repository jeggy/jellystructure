pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

// R333 (FR-R333-2) — `:captureFlatpakSources` records every artefact Gradle downloads, for the Flatpak manifest's
// offline sources file. A settings plugin, so it sees the downloads from the first resolution; it does nothing in a
// build that never runs its task.
plugins {
    id("org.meshtastic.flatpak.sources.settings") version "0.2.2"
}

rootProject.name = "jellystructure"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// R333 (FR-R333-1) — `-Pravilo.desktopOnly=true`: the Flatpak's build. Flathub builds offline in a sandbox with no
// Android SDK, no Kotlin/Native toolchain and no Node, so this configures ONLY what `:ravilo-desktop` needs — five
// modules, each with its `jvm("desktop")` target alone. The root project (the Kotlin/Native backend) declares no
// targets. Every other build ignores the property. ci.yml compiles the desktop app both ways.
val desktopOnly: Boolean = providers.gradleProperty("ravilo.desktopOnly").orNull == "true"

include(":shared")
include(":ravilo-i18n") // R279 — the one string table, generated from i18n/*.json; every client reads it
include(":ravilo-ui")
include(":ravilo-castv2") // R330 — a Cast v2 sender in plain Kotlin/JVM (framing, channels, state machine); the Mac app's way to a speaker
include(":ravilo-desktop") // R328 — Ravilo on the Mac (and R333 on Linux): the Compose Desktop application, packaged as a .dmg by R331
if (!desktopOnly) {
    include(":ravilo-web")
    include(":ravilo-screen") // R264 — receiver-only TV app (Tizen first, webOS as a follow-on package); supersedes R189/:ravilo-tizen
    include(":ravilo-receiver-core") // R264 — the CAF-free receiver state machine shared with :ravilo-cast
    include(":ravilo-cast")  // R245 — the Chromecast receiver (Kotlin/JS + CAF), served at /cast/ (218)
    // FR-167-5 — tiny standalone linuxX64 static-file server (Ktor CIO), deliberately not dependent on the
    // root project (which would drag in the whole backend's SQLDelight/config/media stack). Packages the
    // ravilo-web wasmJs bundle as a plain Kotlin service with no reverse-proxy technology baked in.
    include(":web-static-server")
}

// :ravilo-android requires Android SDK — only include when sdk.dir is configured in
// local.properties (or ANDROID_HOME is set in the environment).
val hasAndroidSdk: Boolean = run {
    val lp = file("local.properties")
    if (lp.exists()) {
        val props = java.util.Properties().also { it.load(lp.inputStream()) }
        props.getProperty("sdk.dir") != null
    } else {
        System.getenv("ANDROID_HOME") != null
    }
}
if (hasAndroidSdk && !desktopOnly) {
    include(":ravilo-player")
    include(":ravilo-android") // R224 — universal app: TV (leanback) + phone entry points, one APK/listing
    include(":ravilo-android-benchmark") // R213 — generates ravilo-android's Baseline Profile
}
