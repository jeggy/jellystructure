import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    kotlin("android")
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.androidx.baselineprofile) // R213
}

android {
    namespace = "dev.jellystructure.ravilo"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.jellystructure.ravilo"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // release config: reads from local.properties; falls back to the well-known debug key
        // so `assembleRelease` works out-of-the-box for sideloading without manual keystore setup.
        // To use a real keystore add these four lines to local.properties:
        //   keystore.file=<absolute path to .keystore / .jks>
        //   keystore.password=<store password>
        //   keystore.alias=<key alias>
        //   keystore.keyPassword=<key password>
        create("release") {
            val lp = Properties().also { p ->
                rootProject.file("local.properties").takeIf { it.exists() }?.let { p.load(it.reader()) }
            }
            val ksFile = (lp["keystore.file"] as? String)?.let { file(it) }
            storeFile     = ksFile ?: file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = (lp["keystore.password"]    as? String) ?: "android"
            keyAlias      = (lp["keystore.alias"]       as? String) ?: "androiddebugkey"
            keyPassword   = (lp["keystore.keyPassword"] as? String) ?: "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
        }
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            // Opt-in dev convenience: `-Pravilo.releaseAsDebugId` suffixes the release id with
            // `.debug` so a release (non-debuggable, R8) build installs as an in-place update over
            // the paired debug app — lets you A/B the *real* on-device performance without
            // re-pairing (R43 perf work showed the debug build, not the animation, was the lag).
            // Off by default so production release builds keep the clean application id.
            if (project.hasProperty("ravilo.releaseAsDebugId")) {
                applicationIdSuffix = ".debug"
                versionNameSuffix   = "-rel"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // Keep APK filename readable: ravilo-1.0-release.apk
    applicationVariants.all {
        val v = this
        v.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "ravilo-${v.versionName}-${v.buildType.name}.apk"
        }
    }
}

// R213 — ./gradlew :ravilo-android:generateBaselineProfile drives :ravilo-android-benchmark's
// journey against a connected device and writes the result to src/main/baseline-prof.txt. No
// managed-device is configured (none of this environment's Android SDK setups include one), so this
// runs against whatever real device/emulator is connected via adb — plugin default behavior.
baselineProfile {
    warnings {
        maxAgpVersion = false // this plugin predates AGP 9.0.1; the mismatch is a known, accepted gap
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(projects.raviloUi)
    implementation(projects.raviloPlayer)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.leanback)
    // R103: applies the (library-merged + app) baseline profile on first run. Without it a sideloaded
    // release APK never AOT-compiles the profiled methods, so every launch runs JIT — the measured
    // cold-start scroll jank (~64% janky frames cold vs ~0.2% once AOT-compiled).
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    baselineProfile(project(":ravilo-android-benchmark")) // R213
}
