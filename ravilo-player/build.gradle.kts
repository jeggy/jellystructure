plugins {
    alias(libs.plugins.android.library)
    kotlin("android")
}

// ─── GPL-containment boundary (R31) ────────────────────────────────────────────
// This Android-only module is the *only* place the GPL-3.0 jellyfin FFmpeg decoder is linked.
// Only :ravilo-android depends on it; :ravilo-ui and :ravilo-web never do, so the shared UI and
// the web bundle stay free of the GPL dependency. See NOTICE. (Spec R31 detail is in git history —
// the older Ravilo specs were pruned 2026-06-30; see specs/ravilo/requirements/README.md.)

android {
    namespace = "dev.jellystructure.ravilo.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    // R294 — the extractor tests read the MKV fixtures in src/test/resources under Robolectric.
    testOptions { unitTests { isIncludeAndroidResources = true } }

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
    // R294 — the vendored RaviloMatroskaExtractor keeps upstream's annotations verbatim (so it stays
    // diff-able against Media3), and Media3 declares these compile-only, so they are not transitive.
    compileOnly("androidx.annotation:annotation:1.6.0")
    compileOnly("org.checkerframework:checker-qual:3.43.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation("androidx.media3:media3-test-utils:1.8.0")
    testImplementation("androidx.media3:media3-test-utils-robolectric:1.8.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
