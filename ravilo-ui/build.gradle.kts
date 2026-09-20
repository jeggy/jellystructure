@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "dev.jellystructure.ravilo.ui"
    compileSdk = 36
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
        browser {
            // Phase 198 — `browser()` creates a wasmJsBrowserTest task that `check`/`allTests` both
            // depend on, and it FAILS ("test sources present … did not discover any tests") because
            // commonTest compiles for wasmJs while no headless browser is configured here — see the
            // commonTest comment below for why that is deliberate (R196). Disabling the task says the
            // same thing honestly: it is skipped, not failed. A `check` that is red for a non-problem
            // is exactly the signal-destroying pattern phase 197 was written about.
            testTask { enabled = false }
        }
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
                api(projects.raviloI18n) // R279 — the generated string table; `api` so screens can name RaviloLanguage
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
                implementation(libs.play.services.cast.framework) // R245 — the Cast sender (CastContext, RemoteMediaClient, MediaRouteButton)
                implementation(libs.androidx.mediarouter)         // R245 — MediaRouteButton / MediaTransferReceiver (Output Switcher)
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
