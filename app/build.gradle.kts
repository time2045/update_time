plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// CI 版本号：GitHub Actions 通过 -PciBuildNumber=N 传入运行次数，
// 本地未传时用 0。versionName = 1.0.运行次数（如 1.0.37），versionCode 随之递增，
// 这样每次编译都能直接覆盖安装旧版本。
val ciBuildNumber = (project.findProperty("ciBuildNumber") as String?)?.toIntOrNull() ?: 0

android {
    namespace = "com.example.ntpsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ntpsync"
        // minSdk 26：DevicePolicyManager.setTime() 需要 API 26+，
        // java.time 显示时间也需要 API 26+（避免 desugaring）。
        minSdk = 26
        targetSdk = 35
        versionCode = 100 + ciBuildNumber
        versionName = "1.0.$ciBuildNumber"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // CI 产物直接用 debug 签名，保证可直接安装。
            // 正式发布请替换为自己的 keystore。
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
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // NTP 服务器地址持久化（不使用 SQLite）
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
