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
        targetSdk = 36
        versionCode = 22
        versionName = "2.6.0"
    }

    // Signing: GitHub Secrets lo unna private release key matrame (repo lo key ledu).
    val ksPath = System.getenv("FLIKK_KEYSTORE_FILE")
    val ksPass = System.getenv("FLIKK_KEY_PASSWORD")
    val hasKey = !ksPath.isNullOrBlank() && !ksPass.isNullOrBlank()
    signingConfigs {
        if (hasKey) {
            create("flikk") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = "flikkplay"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        debug {
            if (hasKey) signingConfig = signingConfigs.getByName("flikk")
        }
        release {
            isMinifyEnabled = false
            if (hasKey) signingConfig = signingConfigs.getByName("flikk")
            else logger.warn("FLIKK_KEYSTORE / FLIKK_KEY_PASSWORD secrets levu: release APK sign avvadu")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
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
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.gms:play-services-cast-framework:22.1.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    // Network streams (VLC laga): HLS (.m3u8) + DASH (.mpd)
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    // Trim (video clip cut chesi save)
    implementation("androidx.media3:media3-transformer:$media3")
    // AC3 / E-AC3 / DTS / TrueHD audio (MKV movies lo common) — FFmpeg decoder, GPL-3.0
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    implementation("com.github.bumptech.glide:glide:4.16.0")

    // PC / NAS shared folders (SMB, Windows "Share" folder) — Wi-Fi lo stream
    implementation("com.hierynomus:smbj:0.13.0")
}
