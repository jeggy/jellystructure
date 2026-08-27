// R213 — a com.android.test module: instrumentation-only, no APK of its own. Runs against a real
// device/emulator (never a household TV, per the phase spec) and drives ravilo-android through a
// cold-start/scroll/detail-nav journey to generate a real Baseline Profile, replacing the hand-
// authored wildcard file. Not wired into CI (no device available there) — regeneration is a manual/
// periodic task, see README.md.
plugins {
    // No version here (unlike com.android.application/library elsewhere): AGP's `com.android.test`
    // plugin is the same artifact already on the buildscript classpath via those other modules —
    // requesting an explicit version here conflicts with Gradle's plugin resolution ("already on the
    // classpath with an unknown version"). Resolves to whatever AGP version those already pinned.
    id("com.android.test")
    // Same reasoning applies to Kotlin: ravilo-android/ravilo-phone/ravilo-player all apply this via
    // the `kotlin("android")` shorthand (an ambient version, not the catalog's pinned 2.3.21) —
    // matching that exactly, rather than `alias(libs.plugins.kotlin.android)`, avoids the identical
    // "already on the classpath with an unknown version" conflict for Kotlin's plugin ID too.
    kotlin("android")
    // Needed on THIS (producer) side too, not just :ravilo-android (consumer) — without it, this
    // module has no outgoing "baselineProfile"-usage variant at all, only ordinary "runtime" ones,
    // which :ravilo-android's consumer configuration can't match regardless of buildType.
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "dev.jellystructure.ravilo.benchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 28 // androidx.benchmark's own floor for reliable Baseline Profile collection
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instruments the real app module, not a separate APK of its own.
    targetProjectPath = ":ravilo-android"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    // A com.android.test module only has a "debug" buildType by default — the baselineprofile
    // plugin's consumer configuration on :ravilo-android specifically wants a producer variant
    // attributed buildType "release" (it profiles against ravilo-android's release-shaped
    // "nonMinifiedRelease" variant, which shares the "release" buildType attribute even though
    // minification is off during collection). A plain, empty "release" buildType here is enough to
    // satisfy that attribute match — no minification/signing config needed on this side.
    buildTypes {
        create("release") {}
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Matches ravilo-android's own approach: target bytecode level 11 without demanding an actual JDK 11
// toolchain (kotlin { jvmToolchain(11) } tried to auto-provision one and failed — none is installed
// and toolchain download repos aren't configured in this environment).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
