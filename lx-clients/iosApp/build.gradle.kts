plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        val iosMain by getting {
            dependencies {
                implementation(project(":shared"))
                // startKoin / GlobalContext 需要 koin-core（shared 模块用 implementation，不传递）
                implementation(libs.koin.core)
            }
        }
    }
}
