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

    sourceSets {
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
