// FR-167-5 — a tiny, standalone linuxX64 static-file server. Deliberately NOT a dependent of the root
// project (which would drag in SQLDelight/sqlite, ktor-client-curl, config/media logic — far too heavy
// for "serve some static files"). Ktor CIO only, same engine the main backend uses.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "dev.jellystructure.webstatic.main"
                baseName = "web-static-server"
            }
        }
    }

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.kotlinx.io.core)
            }
        }
    }
}
