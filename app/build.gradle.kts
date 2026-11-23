import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drishya.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flikk.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Fixed key: prathi kotha build paata app meedha update ga install avthundi.
    // Play Store ki vellettappudu private key vaadandi (ee file public repo lo undi).
    signingConfigs {
        create("flikk") {
            storeFile = file("flikk.keystore")
            storePassword = "flikk123"
            keyAlias = "flikk"
            keyPassword = "flikk123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("flikk")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("flikk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val media3 = "1.9.0"

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    // AC3 / E-AC3 / DTS / TrueHD audio (MKV movies lo common) — FFmpeg decoder, GPL-3.0
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    implementation("com.github.bumptech.glide:glide:4.16.0")
}
