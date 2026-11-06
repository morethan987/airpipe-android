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
        versionCode = 6
        versionName = "1.3.1"
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
    // 纯框架 API，零 AndroidX；zstd-jni 是唯一的三方依赖（v2 传输压缩，
    // AAR 内置各 ABI 的 libzstd.so，见 docs/transfer-perf.md §7）
    implementation("com.github.luben:zstd-jni:1.5.7-1@aar")
}
