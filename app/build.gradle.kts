plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.morethan.airpipe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.morethan.airpipe"
        // minSdk 29 (Android 10): 剪贴板焦点限制从 10 开始
        minSdk = 29
        // targetSdk 34 (Android 14): 强制 FGS 类型 + 通知运行时权限，
        // 避开 Android 15 的 dataSync 前台服务 6 小时超时
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 纯框架 API，零 AndroidX、零三方依赖
}
