import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 讯飞密钥：写在工程根目录 local.properties（勿提交仓库）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val xfyunAppId = localProps.getProperty("XFYUN_APP_ID", "")
val xfyunApiKey = localProps.getProperty("XFYUN_API_KEY", "")
val xfyunApiSecret = localProps.getProperty("XFYUN_API_SECRET", "")

android {
    namespace = "com.eyewear.transcribe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eyewear.transcribe"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "XFYUN_APP_ID", "\"$xfyunAppId\"")
        buildConfigField("String", "XFYUN_API_KEY", "\"$xfyunApiKey\"")
        buildConfigField("String", "XFYUN_API_SECRET", "\"$xfyunApiSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Cloud ASR WebSocket (plug your provider behind AsrEngine)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
