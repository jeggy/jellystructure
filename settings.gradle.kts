rootProject.name = "jellystructure"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":shared")
include(":ravilo-i18n") // R279 — the one string table, generated from i18n/*.json; every client reads it
include(":ravilo-ui")
include(":ravilo-web")
include(":ravilo-screen") // R264 — receiver-only TV app (Tizen first, webOS as a follow-on package); supersedes R189/:ravilo-tizen
include(":ravilo-receiver-core") // R264 — the CAF-free receiver state machine shared with :ravilo-cast
include(":ravilo-cast")  // R245 — the Chromecast receiver (Kotlin/JS + CAF), served at /cast/ (218)
// FR-167-5 — tiny standalone linuxX64 static-file server (Ktor CIO), deliberately not dependent on the
// root project (which would drag in the whole backend's SQLDelight/config/media stack). Packages the
// ravilo-web wasmJs bundle as a plain Kotlin service with no reverse-proxy technology baked in.
include(":web-static-server")

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
if (hasAndroidSdk) {
    include(":ravilo-player")
    include(":ravilo-android") // R224 — universal app: TV (leanback) + phone entry points, one APK/listing
    include(":ravilo-android-benchmark") // R213 — generates ravilo-android's Baseline Profile
}

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

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
