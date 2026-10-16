plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.easytranslate.chichu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.easytranslate.chichu"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("zh", "en")
    }

    // 签名配置：密钥文件（app/keystore/qingyi.jks）不随公开仓库分发，
    // 本地存在时才启用 release 签名；公开仓库克隆后未放置密钥则打出未签名包。
    signingConfigs {
        if (file("keystore/qingyi.jks").exists()) {
            create("release") {
                storeFile = file("keystore/qingyi.jks")
                storePassword = "qingyi2026"
                keyAlias = "qingyi"
                keyPassword = "qingyi2026"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    splits {
        abi {
            // 手表只需 ARM：生成 arm64-v8a（64 位）与 armeabi-v7a（32 位老手表）两个包
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // 保留 32 位 (armeabi-v7a) 与 64 位 (arm64-v8a) 双 ABI，老手表也能用
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP 9 内置 Kotlin：编译选项用顶层 kotlin {} 块（替代已废弃的 android.kotlinOptions）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    // Wear OS 官方 Compose 库（Material 3 for Wear）
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)

    // 本地推理（语音识别 + 翻译），纯离线
    implementation(libs.onnxruntime.android)

    // 内置下载器（模型网络下载兜底，多线程 + 断点续传）
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
