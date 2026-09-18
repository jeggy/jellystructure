// R245 / 218 — the Ravilo Chromecast receiver: a Kotlin/JS client of :shared (the same TvApiClient the
// TV uses) plus thin CAF glue, bundled to one file and served by jellystructure at /cast/ (218 FR-218-1).
// Same shape as :ravilo-screen (the other plain-JS receiver, R264) — both depend on
// :ravilo-receiver-core for the CAF-free pieces (i18n, track-list construction, small pure helpers).
// `syncCastReceiver` drops the production bundle next to cast-receiver/index.html, which is the
// directory Main.kt serves in a dev checkout and the Dockerfile copies into the image.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    js(IR) {
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "ravilo-cast.js"
            }
        }
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(projects.shared)
                implementation(projects.raviloReceiverCore)
                implementation(libs.ktor.client.js)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

tasks.register<Copy>("syncCastReceiver") {
    description = "Copy the production receiver bundle next to cast-receiver/index.html"
    group = "application"
    dependsOn("jsBrowserProductionWebpack")
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) { include("ravilo-cast.js") }
    into(rootProject.layout.projectDirectory.dir("cast-receiver"))
}
