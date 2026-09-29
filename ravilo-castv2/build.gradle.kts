plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// R330 — a Cast v2 SENDER in plain Kotlin. Protocol only: the length-prefixed protobuf framing, the
// connection/heartbeat/receiver/media channels, and the state machine that turns what a device says into one
// status. The socket (TLS to port 8009) and discovery (mDNS) are the only JVM-shaped parts and live in jvmMain.
// Nothing here knows Ravilo's screens; ravilo-ui's desktopMain adapts it to the CastSender seam.
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.jmdns) // discovery on the Linux dev build; the Mac browses with Bonjour (ravilo-ui)
            }
        }
    }
}
