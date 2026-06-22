@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    // Required so the executable collects Compose resources (the fonts in ravilo-ui's
    // composeResources/) into the web output — without it they 404 at runtime.
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "ravilo.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(projects.raviloUi)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
            }
        }
    }
}
