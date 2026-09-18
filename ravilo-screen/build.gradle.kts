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
            }
        }
    }
}

tasks.register<Copy>("syncScreenReceiver") {
    description = "Copy the production ravilo-screen bundle + web assets next to the .wgt project root"
    group = "application"
    dependsOn("jsBrowserProductionWebpack")
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) { include("ravilo-screen.js") }
    into(rootProject.layout.projectDirectory.dir("ravilo-screen/wgt"))
}
