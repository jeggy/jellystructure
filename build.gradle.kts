@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "dev.jellystructure.main"
                baseName = "jellystructure"
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
    description = "Compile backend, then start API server (port 9505) + frontend dev server (port 8081). Run './gradlew buildFrontend' first if the frontend has never been built."
    group = "application"
    dependsOn("linkDebugExecutableLinuxX64")

    doLast {
        // Kill any leftover instances from previous runs before starting fresh.
        ProcessBuilder("pkill", "-f", "jellystructure.kexe").inheritIO().start().waitFor()
        ProcessBuilder("pkill", "-f", "webpack-dev-server").inheritIO().start().waitFor()
        Thread.sleep(500)

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
            ?: error("Node.js not found in ~/.gradle/nodejs — run './gradlew buildFrontend' first")

        // Locate webpack-dev-server in Kotlin's yarn tooling (~/.kotlin/kotlin-npm-tooling/)
        val yarnTooling = File(System.getProperty("user.home"), ".kotlin/kotlin-npm-tooling/yarn")
        val toolingNodeModules = yarnTooling.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { it.resolve("node_modules").takeIf { f -> f.isDirectory } }
            ?.firstOrNull()
            ?: error("Kotlin npm tooling not found in ~/.kotlin/kotlin-npm-tooling — run './gradlew buildFrontend' first")
        val webpackDevServer = toolingNodeModules.resolve(".bin/webpack-dev-server")

        val webpackConfig = layout.buildDirectory.file("wasm/packages/jellystructure/webpack.config.js").get().asFile
        if (!webpackConfig.exists()) {
            error("webpack.config.js not found at ${webpackConfig.absolutePath} — run './gradlew buildFrontend' first")
        }

        println("[runDev] Starting frontend dev server on http://localhost:8081")
        println("[runDev] Starting backend API server on http://localhost:9505")

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

        val backend = ProcessBuilder(binary.absolutePath)
            .apply {
                environment()["CONFIG_FILE"] = configDir.resolve("config.toml").absolutePath
                environment()["SESSIONS_FILE"] = configDir.resolve("sessions.json").absolutePath
                environment()["MEDIA_FILE"] = configDir.resolve("media.json").absolutePath
                environment()["FRONTEND_DIR"] = frontendDir.absolutePath
                environment()["SERVER_PORT"] = "9505"
            }
            .start()
            .also { it.pipeToGradle("be") }

        Runtime.getRuntime().addShutdownHook(Thread {
            backend.destroyForcibly()
            frontend.destroyForcibly()
        })

        val exitCode = backend.waitFor()
        frontend.destroyForcibly()
        if (exitCode != 0) error("Backend exited with code $exitCode — see output above")
    }
}

tasks.register("buildFrontend") {
    description = "Build the WASM frontend bundle (run once before runDev, or with --continuous for hot reload)."
    group = "application"
    dependsOn("wasmJsBrowserDevelopmentWebpack")
}

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val linuxX64Main by getting {
            dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.cors)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.curl)
                implementation(libs.ktor.client.content.negotiation)
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
