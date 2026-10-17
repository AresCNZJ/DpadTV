import java.util.Properties

plugins {
    id("com.android.application")
}

// 本机私有默认值（local.properties 不入库）。开源仓库里不含任何真实地址或密码。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dpadtv.client"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dpadtv.client"
        // 23 = Android 6.0：Media3 的下限之上，老盒子都能装
        minSdk = 23
        targetSdk = 37
        versionCode = 100
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "DEFAULT_URL", "\"${localProps.getProperty("dpadtv.defaultUrl") ?: ""}\"")
        buildConfigField("String", "DEFAULT_PASSWORD", "\"${localProps.getProperty("dpadtv.defaultPassword") ?: ""}\"")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 原生播放：ExoPlayer 对 HLS 的容错远好于 MediaPlayer，H.265 取决于 SoC 硬解
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // 二维码生成（纯 Java，仅 core，不含 Android 集成包）
    implementation("com.google.zxing:core:3.5.3")
}
