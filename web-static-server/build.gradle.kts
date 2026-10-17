// FR-167-5 — a tiny, standalone linuxX64 (and, DEB-1490, linuxArm64) static-file server. Deliberately NOT a dependent of the root
// project (which would drag in SQLDelight/sqlite, ktor-client-curl, config/media logic — far too heavy
// for "serve some static files"). Ktor CIO only, same engine the main backend uses.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // DEB-1490 FR-1 — the arm64 image cross-compiles the same server; both targets compile src/linuxX64Main/kotlin.
    listOf(linuxX64(), linuxArm64()).forEach { target ->
        target.binaries {
            executable {
                entryPoint = "dev.jellystructure.webstatic.main"
                baseName = "web-static-server"
            }
        }
    }

    sourceSets {
        // The default hierarchy template is off (gradle.properties), so the intermediate set is declared by hand.
        val linuxMain by creating {
            dependsOn(getByName("commonMain"))
            kotlin.srcDir("src/linuxX64Main/kotlin")
            dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                // FR-235-5 — HEAD answers every GET route with the same headers and no body.
                implementation(libs.ktor.server.auto.head.response)
                implementation(libs.kotlinx.io.core)
            }
        }
        val linuxX64Main by getting {
            dependsOn(linuxMain)
            kotlin.setSrcDirs(emptyList<String>())
        }
        val linuxArm64Main by getting { dependsOn(linuxMain) }
    }
}
