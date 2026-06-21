plugins {
    alias(libs.plugins.android.library)
    kotlin("android")
}

// ─── GPL-containment boundary (R31) ────────────────────────────────────────────
// This Android-only module is the *only* place the GPL-3.0 jellyfin FFmpeg decoder is linked.
// Only :ravilo-android depends on it; :ravilo-ui and :ravilo-web never do, so the shared UI and
// the web bundle stay free of the GPL dependency. See NOTICE and
// specs/ravilo/requirements/phase-R31-player-engine-fork.md.

android {
    namespace = "dev.jellystructure.ravilo.player"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // RenderersFactory / DefaultRenderersFactory are @UnstableApi.
        freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
    }
}

dependencies {
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.jellyfin.media3.ffmpeg.decoder)
}
