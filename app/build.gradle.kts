import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.nahida.touchmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nahida.touchmap"
        // Android 13 (API 33) 及以上设备均可安装；targetSdk 用 2026 年最新
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.7.1"
    }

    // 双版本：
    // - full 满血版：发射端 + 接收端 + 本机注入（全部功能）
    // - sender 精简版：仅发射端（操作层），备用机低配专用
    flavorDimensions += "mode"
    productFlavors {
        create("full") {
            dimension = "mode"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "IS_FULL", "true")
        }
        create("sender") {
            dimension = "mode"
            applicationIdSuffix = ".sender"
            versionNameSuffix = "-sender"
            buildConfigField("boolean", "IS_FULL", "false")
        }
    }

    signingConfigs {
        create("release") {
            val ksB64 = System.getenv("KEYSTORE_BASE64")
            if (!ksB64.isNullOrEmpty()) {
                // CI：从环境变量解码统一签名 keystore（GitHub Secrets 注入）
                val ksFile = File(
                    System.getenv("RUNNER_TEMP") ?: System.getProperty("java.io.tmpdir"),
                    "genshintouchmap-release.keystore"
                )
                ksFile.writeBytes(Base64.getDecoder().decode(ksB64))
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // 本地无密钥：fallback debug 签名保证可构建
                val debug = signingConfigs.getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 统一签名：CI 用固定 keystore，本地 fallback debug
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        // Kotlin 2.3+ 推荐的新 DSL（kotlinOptions 已废弃）
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose (Material 3 / Material You)
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // View 版 Material 组件（提供 Theme.Material3.* 应用主题）
    implementation("com.google.android.material:material:1.12.0")

    // DataStore（替代 SharedPreferences）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // kotlinx.serialization（JSON 配置持久化）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Shizuku（高权限系统服务调用：输入注入引擎 B）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
