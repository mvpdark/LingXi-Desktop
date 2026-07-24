import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
    }
}

android {
    namespace = "com.mvpdark.lx"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mvpdark.lx"
        minSdk = 24
        targetSdk = 36
        // 版本号：优先从环境变量读取（CI 注入），本地默认 1
        versionCode = (System.getenv("LX_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("LX_VERSION_NAME") ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名配置：从环境变量读取（GitHub Actions 注入），本地回退到 debug
    // 注意：不在此处 throw GradleException，因为 Desktop 构建也会配置此模块
    // CI 的 keystore 强制检查由 workflow 的 "Decode release keystore" 步骤保证
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("LX_KEYSTORE_PATH")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("LX_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("LX_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("LX_KEY_PASSWORD") ?: ""
            } else {
                // 本地开发、Desktop 构建、或 CI 未注入 keystore：使用 debug keystore
                storeFile = signingConfigs.getByName("debug").storeFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)

    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.test)
}
