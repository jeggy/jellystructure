// R264 — the CAF-free pieces of a Ravilo receiver (i18n, small pure helpers, ScreenStatus/track
// construction), shared by :ravilo-cast (Chromecast, CAF) and :ravilo-screen (Tizen AVPlay / webOS
// <video>+hls.js). Extracted from ravilo-cast/Receiver.kt in its own no-behaviour-change commit — see
// that phase's dev review, item 3: the CAF wiring itself (LOAD interceptor, playerManager, custom
// namespace messages) stays in :ravilo-cast, since abstracting it without a live Chromecast to verify
// against would be the riskier refactor, not this one.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    js(IR) {
        browser()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(projects.shared)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
