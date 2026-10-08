import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    kotlin("android")
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.androidx.baselineprofile) // R213
}

/** R266 — the development Cast application id for debug builds: `-PraviloCastDevAppId=…`, else local.properties'
 *  `raviloCastDevAppId`, else empty. Only 8 hex characters are passed on; anything else is empty (a typo must not
 *  silently cast to nothing). */
fun castDevAppId(): String {
    val lp = Properties().also { p -> rootProject.file("local.properties").takeIf { it.exists() }?.let { p.load(it.reader()) } }
    val raw = ((project.findProperty("raviloCastDevAppId") as String?) ?: (lp["raviloCastDevAppId"] as? String)).orEmpty().trim()
    return if (Regex("^[0-9A-Fa-f]{8}$").matches(raw)) raw.uppercase() else ""
}

android {
    namespace = "dev.jellystructure.ravilo"
    compileSdk = 36

    defaultConfig {
        // Phase 226 — from gradle.properties, so the admin card's "Package Name" can never drift from it.
        applicationId = providers.gradleProperty("ravilo.applicationId").get()
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = 36
        // R215: deploy-play-store.yml overrides both via -Pravilo.versionCode/-Pravilo.versionName,
        // derived from the release tag. Unset for local/sideload builds, which keep versionCode 1.
        // R252 (FR-R252-1): versionName is the root project's resolved buildVersion — which already
        // prefers -Pravilo.versionName and otherwise falls back to `git describe` — so the installed
        // package's versionName and :shared's BuildInfo.version agree by construction, and a sideload
        // reads "1.18-3-g6d4499e" rather than a "1.0" that meant nothing.
        versionCode = (project.findProperty("ravilo.versionCode") as String?)?.toInt() ?: 1
        versionName = rootProject.extra["buildVersion"] as String
    }

    signingConfigs {
        // release config: reads from (in priority order) Gradle project properties — CI, see R215's
        // deploy-play-store.yml — then local.properties, then falls back to the well-known debug key
        // so `assembleRelease` works out-of-the-box for sideloading without any keystore setup at all.
        // To use a real keystore locally add these four lines to local.properties:
        //   keystore.file=<absolute path to .keystore / .jks>
        //   keystore.password=<store password>
        //   keystore.alias=<key alias>
        //   keystore.keyPassword=<key password>
        create("release") {
            val lp = Properties().also { p ->
                rootProject.file("local.properties").takeIf { it.exists() }?.let { p.load(it.reader()) }
            }
            fun keystoreProp(ciKey: String, localKey: String): String? =
                (project.findProperty(ciKey) as String?) ?: (lp[localKey] as? String)

            val ksFile = keystoreProp("ravilo.keystore.file", "keystore.file")?.let { file(it) }
            storeFile     = ksFile ?: file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = keystoreProp("ravilo.keystore.password", "keystore.password") ?: "android"
            keyAlias      = keystoreProp("ravilo.keystore.alias", "keystore.alias") ?: "androiddebugkey"
            keyPassword   = keystoreProp("ravilo.keystore.keyPassword", "keystore.keyPassword") ?: "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            // R266 (owner, 2026-10-05) — a debug build casts with the DEVELOPMENT Cast application (its Android TV
            // package is dev.jellystructure.ravilo.debug, so Cast Connect can launch a debug TV build), in place of the
            // server's chromecast.app_id. From -PraviloCastDevAppId or local.properties' raviloCastDevAppId (never
            // committed); empty = the server's id, exactly as before.
            buildConfigField("String", "CAST_DEV_APP_ID", "\"${castDevAppId()}\"")
        }
        release {
            // R266 — a release build always casts with the server's application id.
            buildConfigField("String", "CAST_DEV_APP_ID", "\"\"")
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
            ndk {
                // Play Console warns on native code (Media3/ExoPlayer's .so libs) shipped with no
                // debug symbols — FULL embeds them in the AAB so Play can auto-extract for crash/ANR
                // symbolication, no separate upload step needed.
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true   // R266 — CAST_DEV_APP_ID
    }

    // The release APK is named like every other release asset — ravilo-<platform>-<version>.<extension>
    // (owner, 2026-09-30: ravilo-android-1.47.apk beside ravilo-tizen-1.47.wgt, ravilo-mac-1.47.dmg and
    // ravilo-linux-1.47.flatpak; was ravilo-1.47-release.apk). A debug build keeps its suffix so the two never look alike.
    applicationVariants.all {
        val v = this
        v.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = if (v.buildType.name == "release") "ravilo-android-${v.versionName}.apk" else "ravilo-android-${v.versionName}-${v.buildType.name}.apk"
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
    implementation(libs.androidx.fragment) // R245 amendment: the Cast chooser needs a FragmentActivity host
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.leanback)
    // R103: applies the (library-merged + app) baseline profile on first run. Without it a sideloaded
    // release APK never AOT-compiles the profiled methods, so every launch runs JIT — the measured
    // cold-start scroll jank (~64% janky frames cold vs ~0.2% once AOT-compiled).
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session) // R266 — R44's session, handed to Cast Connect's MediaManager
    implementation(libs.play.services.cast.tv)   // R266 — Cast Connect receiver (TV only at runtime)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    baselineProfile(project(":ravilo-android-benchmark")) // R213
}
