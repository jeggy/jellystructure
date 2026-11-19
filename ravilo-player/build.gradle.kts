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
        minSdk = libs.versions.android.minSdk.get().toInt()
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

// DEB-4745 — Robolectric fetched this jar from Maven Central at test time and CI's runner lost that race
// (429, then FileNotFoundException on the .pom/.sha512). Gradle resolves and caches it with the other
// dependencies instead, and Robolectric reads it from a local directory in offline mode.
val robolectricAndroidAll = listOf("14-robolectric-10818077-i7", "15-robolectric-12650502-i7").map { v ->
    // One configuration per jar: they are one module, and a single configuration would keep only the newest.
    configurations.create("robolectricAndroidAll_" + v.substringBefore('-')) {
        isCanBeConsumed = false
        isTransitive = false
        dependencies.add(project.dependencies.create("org.robolectric:android-all-instrumented:$v"))
    }
}
val robolectricAndroidAllDir = layout.buildDirectory.dir("robolectric-android-all")
val syncRobolectricAndroidAll by tasks.registering(Sync::class) {
    robolectricAndroidAll.forEach { from(it) }
    into(robolectricAndroidAllDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricAndroidAll)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricAndroidAllDir.get().asFile.absolutePath)
}
