@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.net.InetSocketAddress
import java.net.ServerSocket

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    // AGP declared here (apply=false) so its classes are on the shared classpath
    // before KGP's AgpWithBuiltInKotlinAppliedCheck fires in :ravilo-ui/:ravilo-android.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}


sqldelight {
    databases {
        create("JellystructureDb") {
            packageName.set("dev.jellystructure.db")
        }
    }
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "dev.jellystructure.main"
                baseName = "jellystructure"
                // ld.lld doesn't search multiarch lib dirs; --allow-shlib-undefined lets libsqlite3.so's
                // glibc symbol references (pow, dlclose) resolve at runtime via the system linker.
                linkerOpts("-L/usr/lib/x86_64-linux-gnu", "--allow-shlib-undefined")
            }
        }
    }

    wasmJs {
        browser {
            webpackTask {
                mainOutputFileName = "jellystructure.js"
            }
        }
        binaries.executable()
    }

tasks.register<Exec>("runBackend") {
    description = "Compile and run the backend server for local development"
    group = "application"
    dependsOn("linkDebugExecutableLinuxX64")

    val configDir = rootProject.layout.projectDirectory.dir("config").asFile
    val frontendDir = layout.buildDirectory.dir("dist/wasmJs/developmentExecutable")
    val binary = layout.buildDirectory.file("bin/linuxX64/debugExecutable/jellystructure.kexe")

    doFirst { configDir.mkdirs() }

    commandLine(binary.get().asFile.absolutePath)
    environment("CONFIG_FILE", configDir.resolve("config.toml").absolutePath)
    environment("SESSIONS_FILE", configDir.resolve("sessions.json").absolutePath)
    environment("FRONTEND_DIR", frontendDir.get().asFile.absolutePath)
    environment("SERVER_PORT", "9505")
}

tasks.register("runDev") {
    description = "Compile backend + frontend (incremental), then start API server (port 9505) + admin frontend dev server (port 8081) + Ravilo web dev server (port 8082)."
    group = "application"
    dependsOn("linkDebugExecutableLinuxX64", "syncDesignAssets", ":ravilo-web:wasmJsBrowserDevelopmentWebpack")

    // These are set from doLast and read by the buildFinished callback below.
    var backendProc: Process? = null
    var frontendProc: Process? = null
    var raviloWebProc: Process? = null

    // buildFinished fires when the Gradle build ends — including on Ctrl+C cancellation — even
    // when the Gradle daemon JVM stays alive. A JVM shutdown hook alone is not enough because the
    // daemon doesn't exit on Ctrl+C; it stays alive to serve the next build.
    @Suppress("DEPRECATION")
    project.gradle.buildFinished {
        backendProc?.destroyForcibly()
        frontendProc?.destroyForcibly()
        raviloWebProc?.destroyForcibly()
    }

    doLast {
        // Kill any leftover instances from previous runs before starting fresh.
        // Use SIGKILL so the socket is released immediately rather than waiting for graceful shutdown.
        ProcessBuilder("pkill", "-KILL", "-f", "jellystructure.kexe").inheritIO().start().waitFor()
        ProcessBuilder("pkill", "-KILL", "-f", "webpack-dev-server").inheritIO().start().waitFor()
        // Poll until port 9505 is actually bindable (up to 5 s), not just "nothing answers".
        // A plain connect-test (ECONNREFUSED = free) is an insufficient proxy: the OS can still
        // refuse a fresh bind() for a brief window right after SIGKILL-ing the old listener even
        // though nothing is actively accepting connections on the port anymore. That gap crashed the
        // Kotlin/Native backend outright — EADDRINUSE from Ktor's embeddedServer(...).start() escapes
        // its internal engine coroutine uncaught (unlike appScope/rootScope's own launches, which do
        // have a CoroutineExceptionHandler) and aborts the whole process (SIGABRT/134). Testing an
        // actual bind (with SO_REUSEADDR, like a real server would use) is the only proxy that matches
        // what the real server needs to succeed at.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val bindable = try {
                ServerSocket().use { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress("localhost", 9505))
                }
                true
            } catch (_: Exception) {
                false
            }
            if (bindable) break
            Thread.sleep(200)
        }

        val configDir = rootProject.layout.projectDirectory.dir("config").asFile
        configDir.mkdirs()
        val frontendDir = layout.buildDirectory.dir("dist/wasmJs/developmentExecutable").get().asFile
        val binary = layout.buildDirectory.file("bin/linuxX64/debugExecutable/jellystructure.kexe").get().asFile

        // Locate node binary (newest version in ~/.gradle/nodejs/)
        val nodeHome = File(System.getProperty("user.home"), ".gradle/nodejs")
        val node = nodeHome.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("node-") }
            ?.maxByOrNull { it.lastModified() }
            ?.resolve("bin/node")
            ?: error("Node.js not found in ~/.gradle/nodejs")

        // Locate webpack-dev-server in Kotlin's yarn tooling (~/.kotlin/kotlin-npm-tooling/)
        val yarnTooling = File(System.getProperty("user.home"), ".kotlin/kotlin-npm-tooling/yarn")
        val toolingNodeModules = yarnTooling.listFiles()
            ?.filter { it.isDirectory }
            ?.firstNotNullOfOrNull { it.resolve("node_modules").takeIf { f -> f.isDirectory } }
            ?: error("Kotlin npm tooling not found in ~/.kotlin/kotlin-npm-tooling")
        val webpackDevServer = toolingNodeModules.resolve(".bin/webpack-dev-server")

        val webpackConfig = layout.buildDirectory.file("wasm/packages/jellystructure/webpack.config.js").get().asFile
        if (!webpackConfig.exists()) error("webpack.config.js not found at ${webpackConfig.absolutePath}")

        val raviloWebConfig = layout.buildDirectory.file("wasm/packages/jellystructure-ravilo-web/webpack.config.js").get().asFile
        if (!raviloWebConfig.exists()) error("ravilo-web webpack.config.js not found at ${raviloWebConfig.absolutePath}")

        println("[runDev] Starting admin frontend dev server on http://localhost:8081")
        println("[runDev] Starting Ravilo web dev server     on http://localhost:8082")
        println("[runDev] Starting backend API server        on http://localhost:9505")

        fun Process.pipeToGradle(prefix: String) {
            val out = System.out
            Thread {
                inputStream.bufferedReader().forEachLine { out.println("[$prefix] $it") }
            }.apply { isDaemon = true; start() }
            Thread {
                errorStream.bufferedReader().forEachLine { out.println("[$prefix] $it") }
            }.apply { isDaemon = true; start() }
        }

        val frontend = ProcessBuilder(node.absolutePath, webpackDevServer.absolutePath, "--config", webpackConfig.absolutePath)
            .directory(webpackConfig.parentFile)
            .apply {
                environment()["KOTLIN_TOOLING_DIR"] = toolingNodeModules.absolutePath
                environment()["NODE_PATH"] = toolingNodeModules.absolutePath
            }
            .start()
            .also { it.pipeToGradle("fe") }
        frontendProc = frontend

        val raviloWeb = ProcessBuilder(node.absolutePath, webpackDevServer.absolutePath, "--config", raviloWebConfig.absolutePath)
            .directory(raviloWebConfig.parentFile)
            .apply {
                environment()["KOTLIN_TOOLING_DIR"] = toolingNodeModules.absolutePath
                environment()["NODE_PATH"] = toolingNodeModules.absolutePath
            }
            .start()
            .also { it.pipeToGradle("ravilo-web") }
        raviloWebProc = raviloWeb

        // stdbuf -oL forces line-buffered stdout so every println flushes immediately.
        // Without it the C runtime switches to fully-buffered mode when stdout is piped,
        // causing logs to appear in large delayed bursts rather than in real time.
        val backend = ProcessBuilder("stdbuf", "-oL", binary.absolutePath)
            .apply {
                environment()["CONFIG_FILE"] = configDir.resolve("config.toml").absolutePath
                environment()["DB_FILE"] = configDir.resolve("jellystructure.db").absolutePath
                environment()["FRONTEND_DIR"] = frontendDir.absolutePath
                environment()["SERVER_PORT"] = "9505"
            }
            .start()
            .also { it.pipeToGradle("be") }
        backendProc = backend

        // Fallback: if the Gradle daemon JVM ever does exit, clean up then too.
        Runtime.getRuntime().addShutdownHook(Thread {
            backend.destroyForcibly()
            frontend.destroyForcibly()
            raviloWeb.destroyForcibly()
        })

        try {
            val exitCode = backend.waitFor()
            frontend.destroyForcibly()
            raviloWeb.destroyForcibly()
            if (exitCode != 0) error("Backend exited with code $exitCode — see output above")
        } catch (_: InterruptedException) {
            backend.destroyForcibly()
            frontend.destroyForcibly()
            raviloWeb.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }
}

// Assemble the complete frontend dist that the backend serves at FRONTEND_DIR.
// Three sources feed into dist/wasmJs/developmentExecutable/:
//   1. app/          — wf.css and app.css (canonical design tokens)
//   2. kotlin-webpack — jellystructure.js + the content-hashed *.wasm bundle (webpack output)
//   3. wasm/packages/jellystructure/kotlin — index.html (Kotlin resource, processed by wasmJsProcessResources)
tasks.register<Copy>("syncDesignAssets") {
    description = "Assemble the complete frontend dist (CSS + JS bundle + index.html) after webpack"
    group = "application"
    from(rootProject.layout.projectDirectory.dir("design/app")) {
        include("wf.css", "app.css", "seeding.css", "seeding.js")
    }
    from(rootProject.layout.projectDirectory.dir("design")) {
        include("flags.css")
        include("flags/**")
    }
    from(layout.buildDirectory.dir("kotlin-webpack/wasmJs/developmentExecutable")) {
        include("jellystructure.js", "*.wasm")
    }
    from(layout.buildDirectory.dir("wasm/packages/jellystructure/kotlin")) {
        include("index.html")
    }
    into(layout.buildDirectory.dir("dist/wasmJs/developmentExecutable"))
    dependsOn("wasmJsBrowserDevelopmentWebpack")
}

tasks.register("buildFrontend") {
    description = "Build the WASM frontend bundle (run once before runDev, or with --continuous for hot reload)."
    group = "application"
    dependsOn("syncDesignAssets")
}

// Mirror the design assets (CSS + flag sprites) into the production bundle so Docker builds
// pick them up. wasmJsBrowserDistribution produces productionExecutable; syncDesignAssets only
// covers developmentExecutable (used by runDev + the webpack dev server).
tasks.named("wasmJsBrowserDistribution") {
    doLast {
        copy {
            from(rootProject.layout.projectDirectory.dir("design/app")) {
                include("wf.css", "app.css", "seeding.css", "seeding.js")
            }
            from(rootProject.layout.projectDirectory.dir("design")) {
                include("flags.css")
                include("flags/**")
            }
            into(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
        }
    }
}

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
        // Intermediate native source set — SQLDelight generates its DB types here so they are visible
        // to linuxX64Main (and future linuxArm64Main) but NOT to wasmJsMain, avoiding the
        // "wasmJs can't resolve sqldelight:runtime" error. Requires applyDefaultHierarchyTemplate=false.
        val nativeMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }
        val linuxX64Main by getting {
            dependsOn(nativeMain)
            dependencies {
                implementation(projects.shared)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.cors)
                implementation(libs.ktor.server.status.pages)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.curl)
                implementation(libs.ktor.client.content.negotiation)
                // Phase 110 — outbound WS client to Jellyfin's own /socket (session bridge), one per
                // connected Ravilo TV. Already used client-side by ravilo-ui/shared for /api/tv/events.
                implementation(libs.ktor.client.websockets)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.io.core)
                implementation(libs.ktoml.core)
            }
        }
        val linuxX64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(projects.shared)
                implementation(libs.kotlinx.browser)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// SQLDelight 2.0.2 generates code into commonMain and adds `app.cash.sqldelight:runtime` as a
// commonMain dependency. The wasmJs frontend has no SQLite code at all, but it inherits
// commonMain — so it would fail to resolve sqldelight:runtime (no wasmJs artifact exists).
// Fix: after the SQLDelight plugin has wired itself up, reroute its generated source directory
// and its runtime dependency from commonMain to linuxX64Main.
afterEvaluate {
    val kotlin = extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java)
    val generatedDir = layout.buildDirectory
        .dir("generated/sqldelight/code/JellystructureDb/commonMain")
        .get().asFile

    val commonMain = kotlin.sourceSets.getByName("commonMain")
    val linuxX64Main = kotlin.sourceSets.getByName("linuxX64Main")

    // Move generated source directory: commonMain → linuxX64Main
    commonMain.kotlin.setSrcDirs(commonMain.kotlin.srcDirs.filter { it != generatedDir })
    linuxX64Main.kotlin.srcDir(generatedDir)

    // Move the sqldelight:runtime dependency: commonMainApi → linuxX64MainImplementation
    // (SQLDelight adds it to commonMainApi, not commonMainImplementation)
    val rtDeps = configurations.getByName("commonMainApi").dependencies
        .filter { it.group == "app.cash.sqldelight" && it.name == "runtime" }
    rtDeps.forEach { dep ->
        configurations.getByName("commonMainApi").dependencies.remove(dep)
        dependencies.add("linuxX64MainImplementation", dep)
    }
}
