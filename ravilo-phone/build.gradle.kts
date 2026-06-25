import java.util.Properties

// R60 — Android phone (mobile) target. A thin entry module mirroring :ravilo-android, reusing
// :ravilo-ui + :ravilo-player unchanged. Only the manifest + Activity differ (portrait, touch,
// standard launcher, system bars visible). TV remains the primary form factor.
plugins {
    alias(libs.plugins.android.application)
    kotlin("android")
    alias(libs.plugins.kotlin.plugin.compose)
}

android {
    namespace = "dev.jellystructure.ravilo.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.jellystructure.ravilo.phone" // distinct id → installs alongside the TV build
        minSdk = 24                                        // phones; TV module keeps 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
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

    // Keep APK filename readable: ravilo-phone-1.0-release.apk
    applicationVariants.all {
        val v = this
        v.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "ravilo-phone-${v.versionName}-${v.buildType.name}.apk"
        }
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
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
}
