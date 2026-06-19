@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

// To enable the Android target, install the Android SDK, set sdk.dir in local.properties,
// then add:
//   alias(libs.plugins.android.library) to the plugins block,
//   add android { namespace = "..."; compileSdk = 35; defaultConfig { minSdk = 21 } }
//   and androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } } to kotlin { }.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    wasmJs {
        browser()
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.shared)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
            }
        }
    }
}
