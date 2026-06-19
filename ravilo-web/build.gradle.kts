@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
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
                implementation(compose.runtime)
                implementation(compose.ui)
            }
        }
    }
}
