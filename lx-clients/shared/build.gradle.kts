import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets — 暂时禁用，iOS 源集代码保留待 macOS 环境调试
    // 启用方法：取消下面注释，并确保 iosMain 源集用 creating 而非 getting
    // val iosArm64Target = iosArm64()
    // val iosX64Target = iosX64()
    // val iosSimulatorArm64Target = iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Compose Multiplatform
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.json)

            // Koin
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // Coroutines
            implementation(libs.coroutines.core)

            // Serialization
            implementation(libs.serialization.json)

            // Coil
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)

            // Navigation
            implementation(libs.navigation.compose)

            // Lifecycle
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.datastore.preferences)
            // rememberLauncherForActivityResult + ActivityResultContracts 需要 activity-compose
            implementation(libs.androidx.activity.compose)
            // FileProvider（APK 自动更新安装）需要 core-ktx
            implementation(libs.androidx.core.ktx)
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.java)
                implementation(libs.coroutines.swing)
                implementation(compose.desktop.currentOs)
                // JNA — 调用 Windows COM IFileOpenDialog 实现 Win11 原生文件选择器
                implementation(libs.jna.core)
                implementation(libs.jna.platform)
            }
        }

        // iOS 源集 — 暂时禁用（与 iOS target 一起注释）
        // val iosMain by getting {
        //     dependencies {
        //         implementation(libs.ktor.client.darwin)
        //     }
        // }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }

    // iOS framework — 暂时禁用
    // listOf(iosArm64Target, iosX64Target, iosSimulatorArm64Target).forEach { target ->
    //     target.binaries.framework {
    //         baseName = "ComposeApp"
    //         isStatic = true
    //     }
    // }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

android {
    // namespace 仅影响 R 类生成位置，与 applicationId 解耦。
    namespace = "top.mvpdark.lx.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
