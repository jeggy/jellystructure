// R264 — the receiver-only TV app: no navigation, one more media player driven entirely by the
// backend (236). Plain Kotlin/JS IR (no Compose, no wasmJs) — same reasoning R189 established: the
// target hardware's bundled WebKit/Chromium predates WasmGC (and, on 2016-2018 sets, even plain
// WebAssembly). Packaged as a Tizen .wgt first (config.xml at this module's root); webOS is the same
// bundle in an .ipk, a follow-on package once the Tizen one is verified on real hardware.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    js(IR) {
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "ravilo-screen.js"
            }
        }
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(projects.shared)
                implementation(projects.raviloReceiverCore)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.websockets)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // DEB-4674 FR-13: Babel runs in this module's webpack build, never in the widget.
                implementation(devNpm("babel-loader", "9.2.1"))
                implementation(devNpm("@babel/core", "7.28.5"))
                implementation(devNpm("@babel/preset-env", "7.28.5"))
            }
        }
    }
}

tasks.register<Copy>("syncScreenReceiver") {
    description = "Copy the production ravilo-screen bundle + web assets next to the .wgt project root"
    group = "application"
    dependsOn("jsBrowserProductionWebpack")
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) { include("ravilo-screen.js") }
    // DEB-4893 (DEB-4889 FR-19) — the title page's language flags are ravilo-ui's flag_<cc>.png, packed as flags/<cc>.png from that
    // one source; no second set is committed. scripts/check-tizen-assets.sh --packed proves every flag the language table names is here.
    from(rootProject.layout.projectDirectory.dir("ravilo-ui/src/commonMain/composeResources/drawable")) {
        include("flag_*.png")
        rename("flag_(.*)", "$1")
        into("flags")
    }
    into(rootProject.layout.projectDirectory.dir("ravilo-screen/wgt"))
}
