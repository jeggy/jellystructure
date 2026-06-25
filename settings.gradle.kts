rootProject.name = "jellystructure"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":shared")
include(":ravilo-ui")
include(":ravilo-web")

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
