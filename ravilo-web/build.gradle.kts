@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.security.MessageDigest
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

// R263 (FR-R263-5) — generated at build time from the actual distribution, never hand-maintained (dev
// notes). Runs after precompression so it never itself becomes a compression input, though nothing
// here reads the .gz/.br siblings — the server negotiates those transparently regardless of what the
// service worker requests.
val generateServiceWorker by tasks.registering {
    group = "build"
    description = "FR-R263-5 — generates sw.js with a build-time precache manifest."
    val distDir = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
    inputs.dir(distDir)
    outputs.file(distDir.map { it.file("sw.js") })

    doLast {
        val root = distDir.get().asFile
        if (!root.exists()) return@doLast
        val digest = MessageDigest.getInstance("SHA-256")

        // FR-R263-5 — index.html, ravilo.js, both .wasm, composeResources/**, the icons and the
        // self-hosted player libraries (FR-235-9's vendor/, already in this same distribution — not a
        // future R264 dependency; that spec owns the receiver app, not these files). Never
        // runtime-config.js (dev review item 3: env-only, per-deployment, never a property of the
        // bundle — a precached copy would pin an installed app to whatever backend it first saw) and
        // never sw.js itself or its own compressed siblings.
        val excluded = setOf("sw.js", "runtime-config.js")
        val urls = root.walkTopDown()
            .filter { it.isFile }
            .filter { f -> f.extension !in setOf("gz", "br") }
            .filter { f -> f.name !in excluded }
            .filter { f -> f.name != "NOTICE.md" } // vendor/ attribution doc — not part of the app shell
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .sorted()
            .toList()

        for (url in urls) {
            digest.update(url.toByteArray(Charsets.UTF_8))
            digest.update(File(root, url).readBytes())
        }
        val buildId = digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }.take(12)

        val urlsJs = urls.joinToString(",\n  ") { "\"${it}\"" }
        File(root, "sw.js").writeText(
            """
            // Generated by :ravilo-web:generateServiceWorker — do not hand-edit (dev notes: the
            // precache list comes from the distribution at build time, never hand-maintained).
            const CACHE_NAME = "ravilo-$buildId";
            const PRECACHE_URLS = [
              $urlsJs
            ];

            self.addEventListener("install", (event) => {
              // No self.skipWaiting() here — an update installs in the background and only takes over
              // once every open client of the old worker is gone, or the "Ravilo updated" toast's
              // Reload button asks explicitly (see the message handler below). FR-R263-5's own words:
              // "on the next launch it activates" — not "immediately", mid-session.
              event.waitUntil(
                caches.open(CACHE_NAME).then((cache) => cache.addAll(PRECACHE_URLS))
              );
            });

            self.addEventListener("activate", (event) => {
              event.waitUntil(
                caches.keys().then((names) =>
                  Promise.all(
                    names
                      .filter((name) => name.startsWith("ravilo-") && name !== CACHE_NAME)
                      .map((name) => caches.delete(name))
                  )
                ).then(() => self.clients.claim())
              );
            });

            self.addEventListener("message", (event) => {
              if (event.data === "SKIP_WAITING") self.skipWaiting();
            });

            const PRECACHE_PATHS = new Set(PRECACHE_URLS.map((u) => new URL(u, self.registration.scope).pathname));

            self.addEventListener("fetch", (event) => {
              const req = event.request;
              if (req.method !== "GET") return;
              const url = new URL(req.url);
              // FR-R263-5 — everything else (/api/**, Jellyfin, images) is network only: never
              // intercepted, so a fetch failure surfaces exactly as it would with no service worker at
              // all ("no offline mode" — the app's existing connection-error state is the truth here).
              if (!PRECACHE_PATHS.has(url.pathname)) return;
              event.respondWith(
                caches.match(req).then((cached) => cached || fetch(req))
              );
            });
            """.trimIndent() + "\n"
        )
    }
}

tasks.named("wasmJsBrowserDistribution") {
    finalizedBy(precompressWasmJsDistribution, generateServiceWorker)
}
// The precache manifest must see the FINAL bytes (post-compression siblings are excluded, but
// generateServiceWorker still walks the same directory precompressWasmJsDistribution writes into) —
// order between the two finalizers isn't otherwise significant, but making it explicit costs nothing
// and documents the intent.
generateServiceWorker.configure { mustRunAfter(precompressWasmJsDistribution) }

// `GZIPOutputStream`'s public constructors take no Deflater/level — `def` is the inherited
// (DeflaterOutputStream) protected field, reachable from a subclass without reflection. This task runs
// once per build, not per request, so BEST_COMPRESSION is worth paying for — matches the spec's own
// "gzip -9" reference numbers.
class BestCompressionGzipOutputStream(out: java.io.OutputStream) : GZIPOutputStream(out) {
    init { def.setLevel(Deflater.BEST_COMPRESSION) }
}
