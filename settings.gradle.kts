rootProject.name = "jellystructure"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":shared")
include(":ravilo-ui")
include(":ravilo-web")
include(":ravilo-tizen") // R189 — Samsung Tizen TV client (2016-2018 models)
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
    include(":ravilo-android")
    include(":ravilo-phone") // R60 — Android phone (mobile) target
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
