@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.android.library) apply false // R333 — applied below, unless this is the Flatpak's desktop-only build
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)   // R292 — the resume record is one saved string (PlayerResume.kt)
}

// R333 (FR-R333-1) — the Flatpak's build declares only the `desktop` target (see settings.gradle.kts).
val desktopOnly: Boolean = providers.gradleProperty("ravilo.desktopOnly").orNull == "true"

if (!desktopOnly) {
    apply(plugin = libs.plugins.android.library.get().pluginId)
    configure<com.android.build.gradle.LibraryExtension> {
        namespace = "dev.jellystructure.ravilo.ui"
        compileSdk = 36
        defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
        // R343 (FR-R343-9) — the Compose focus-path test renders the series page under Robolectric.
        testOptions { unitTests { isIncludeAndroidResources = true } }
    }
    // R343 — ui-test-manifest registers the empty activity createComposeRule() launches; debug only, never
    // in a release build (the app ships the release variant of this library).
    dependencies { add("debugImplementation", libs.androidx.compose.ui.test.manifest) }
}

kotlin {
    if (!desktopOnly) androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    // R328 — the Mac app. Compose Desktop is a JVM target; the source set is `desktopMain`.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    if (!desktopOnly) wasmJs {
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
                // R316 — no network fetcher in commonMain: each platform names its own (Android: OkHttp,
                // web: Ktor), so none is ever picked up from the classpath.
            }
        }
        // R196 (FR-RV-TRK2-4) — this module's first tests. commonTest's kotlin("test") auto-wires to
        // androidUnitTest (plain JVM, no emulator — the one we actually run) via the default source set
        // hierarchy; wasmJsTest would need a headless browser this sandbox doesn't reliably support, so
        // it's left unconfigured rather than shipping a test task nobody can run.
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)   // R360 — the glyph's 10 s grace, on virtual time
            }
        }
        if (!desktopOnly) sourceSets.getByName("androidMain") {
            dependencies {
                implementation(libs.coil.svg) // SVG channel logos
                implementation(libs.ktor.client.cio) // WebSocket-only client now (R210) — CIO supports WS, the Android engine does not
                // R316 — REST and images over ONE OkHttpClient. R210's `ktor-client-android` (HttpURLConnection)
                // drained a cancelled body on the cancelling thread and crashed a fast scroll with
                // "Unbalanced enter/exit"; OkHttp cancels by closing the socket. R210 named it the fallback.
                implementation(libs.ktor.client.okhttp)
                implementation(libs.coil.network.okhttp)
                implementation("com.squareup.okhttp3:okhttp") { version { strictly(libs.versions.okhttp.get()) } } // see libs.versions.toml
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
        // R343 (FR-R343-9) — the repo's first Compose UI test (SeriesDetailFocusTest): JVM-only, under
        // Robolectric, in CI's existing `:ravilo-ui:testDebugUnitTest` step. Test-only: nothing ships.
        if (!desktopOnly) sourceSets.getByName("androidUnitTest") {
            dependencies {
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.robolectric)
                implementation("junit:junit:4.13.2")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.common) // R328 — WindowPlacement, the AWT/Skiko interop the actuals read
                implementation(libs.kotlinx.coroutines.swing) // Dispatchers.Main for the Mac
                implementation(libs.coil.svg) // SVG channel logos
                implementation(libs.ktor.client.cio) // the WebSocket-only client, as on Android (R210)
                // R316 — one OkHttpClient for REST and images, as on Android; the same strict pin.
                implementation(libs.ktor.client.okhttp)
                implementation(libs.coil.network.okhttp)
                implementation("com.squareup.okhttp3:okhttp") { version { strictly(libs.versions.okhttp.get()) } }
                implementation(libs.jna) // R328 — the Swift library's C exports (MacNative); R335 — libmpv
                implementation(libs.kotlinx.serialization.json) // R335 — mpv's track-list arrives as JSON
                implementation(projects.raviloCastv2) // R330 — the Mac's own Cast v2 sender
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        if (!desktopOnly) sourceSets.getByName("wasmJsMain") {
            dependencies {
                implementation(libs.ktor.client.js)
                implementation(libs.coil.network.ktor) // R316 — images through the browser's fetch; moved here from commonMain
                implementation(libs.coil.svg) // SVG channel logos — was Android-only, browser never decoded them
            }
        }
    }
}
