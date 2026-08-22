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
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 无密钥环境（CI）也保证可安装：release 用 debug 签名（v1+v2）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
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
}
