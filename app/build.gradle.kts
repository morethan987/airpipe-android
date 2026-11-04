import java.util.Properties

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
        versionCode = 3
        versionName = "1.1.0"
    }

    val keystoreProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            storeFile = keystoreProps.getProperty("keystore.file")?.let { file(it) }
            storePassword = keystoreProps.getProperty("keystore.password")
            keyAlias = keystoreProps.getProperty("keystore.alias")
            keyPassword = keystoreProps.getProperty("keystore.password")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
