@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

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

// FR-235-2 (dev review item 4/open question 1) — precompress at build time so no request pays for
// compression at runtime on a Kotlin/Native process with no zlib binding reachable from web-static-server
// (it deliberately doesn't depend on the main backend, which does have one — see GzipCompression.kt).
// .gz siblings always (the JDK alone can do this); .br siblings only when the `brotli` CLI is on PATH,
// which both Dockerfiles' builder stage guarantees but a plain `./gradlew` checkout might not — a
// missing binary is silently skipped, not a build failure.
val precompressWasmJsDistribution by tasks.registering {
    group = "build"
    description = "FR-235-2 — .gz (+ .br where the brotli CLI is available) siblings for the wasmJs distribution."
    val distDir = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
    inputs.dir(distDir)
    outputs.dir(distDir)

    // .gz/.br extensions this task itself may have left from a previous run — never fed back in as
    // input (would double-compress a .gz into a .gz.gz on a second invocation).
    val compressibleExtensions = setOf("wasm", "js", "mjs", "css", "json", "webmanifest", "svg", "html", "txt", "map")

    doLast {
        val root = distDir.get().asFile
        if (!root.exists()) return@doLast
        val hasBrotli = runCatching {
            ProcessBuilder("brotli", "--version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in compressibleExtensions }
            .forEach { file ->
                val gz = File(file.parentFile, "${file.name}.gz")
                BestCompressionGzipOutputStream(gz.outputStream()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                if (hasBrotli) {
                    val br = File(file.parentFile, "${file.name}.br")
                    ProcessBuilder("brotli", "-f", "-q", "11", "-o", br.absolutePath, file.absolutePath)
                        .redirectErrorStream(true).start().waitFor()
                }
            }
    }
}

tasks.named("wasmJsBrowserDistribution") {
    finalizedBy(precompressWasmJsDistribution)
}

// `GZIPOutputStream`'s public constructors take no Deflater/level — `def` is the inherited
// (DeflaterOutputStream) protected field, reachable from a subclass without reflection. This task runs
// once per build, not per request, so BEST_COMPRESSION is worth paying for — matches the spec's own
// "gzip -9" reference numbers.
class BestCompressionGzipOutputStream(out: java.io.OutputStream) : GZIPOutputStream(out) {
    init { def.setLevel(Deflater.BEST_COMPRESSION) }
}
