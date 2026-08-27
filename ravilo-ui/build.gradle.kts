@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "dev.jellystructure.ravilo.ui"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    wasmJs {
        browser()
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.shared)
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.compose.material3)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)
            }
        }
        // R196 (FR-RV-TRK2-4) — this module's first tests. commonTest's kotlin("test") auto-wires to
        // androidUnitTest (plain JVM, no emulator — the one we actually run) via the default source set
        // hierarchy; wasmJsTest would need a headless browser this sandbox doesn't reliably support, so
        // it's left unconfigured rather than shipping a test task nobody can run.
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.coil.svg) // SVG channel logos
                implementation(libs.ktor.client.cio) // WebSocket-only client now (R210) — CIO supports WS, the Android engine does not
                implementation(libs.ktor.client.android) // R210 — REST-only client; routes around a CIO connect bug seen on Android
                implementation(libs.androidx.media3.exoplayer)
                implementation(libs.androidx.media3.exoplayer.hls)
                implementation(libs.androidx.media3.session) // R44: MediaSession for hardware transport keys
                implementation(libs.androidx.media3.ui)      // R55: SubtitleView cue rendering
                implementation(libs.androidx.core)            // WindowCompat/WindowInsetsControllerCompat (PlayerImmersiveEffect)
                implementation(libs.androidx.activity.compose) // BackHandler (PlatformBackHandler bug fix)
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
                implementation(libs.coil.svg) // SVG channel logos — was Android-only, browser never decoded them
            }
        }
    }
}
