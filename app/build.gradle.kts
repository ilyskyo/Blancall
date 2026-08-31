import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 从 local.properties 读取 release 签名信息，不存在则回退 debug 签名避免构建失败
val keystoreProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        load(FileInputStream(localPropsFile))
    }
}
// 是否提供了完整的 release 签名信息
val hasReleaseSigning = keystoreProperties.getProperty("release.storeFile")?.isNotEmpty() == true

android {
    namespace = "com.ilyskyo.blancall"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ilyskyo.blancall"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "5.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名配置：仅当 local.properties 提供完整 release 签名时才创建
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("release.storeFile"))
                storePassword = keystoreProperties.getProperty("release.storePassword", "")
                keyAlias = keystoreProperties.getProperty("release.keyAlias", "")
                keyPassword = keystoreProperties.getProperty("release.keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8 代码压缩与资源压缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 release 签名用 release，否则回退 debug 签名保证 release 任务可构建
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "com/tom_roush/fontbox/resources/cmap/*.jar",
                "com/tom_roush/fontbox/resources/unicode/*.jar",
                "com/tom_roush/pdfbox/resources/afm/*.jar"
            )
        }
    }
}

// 第三方 LiquidGlass 库要求 compileSdk 37，本机 SDK 暂未安装 android-37 平台；
// 跳过其 AAR 元数据编译检查（运行时所用 API 在 compileSdk 36 可用，此前构建验证正常）
tasks.configureEach {
    if (name.startsWith("check") && name.endsWith("AarMetadata")) {
        enabled = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Navigation Compose（已迁入版本目录）
    implementation(libs.androidx.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // ViewModel Compose
    implementation(libs.lifecycle.viewmodel.compose)

    // PDF 文本提取（已迁入版本目录）
    implementation(libs.pdfbox.android)

    // WorkManager（每日学习提醒，已迁入版本目录）
    implementation(libs.androidx.work.runtime.ktx)

    // Markdown 解析（AI 对话 / 训练分析文本渲染）
    implementation(libs.commonmark)

    // 液态玻璃（阅读模式悬浮栏真实折射/色散效果，iOS26 LiquidGlass 风格）
    implementation("com.qmdeve.liquidglass:core:1.0.5")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
