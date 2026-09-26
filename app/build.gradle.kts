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
        versionCode = 41
        versionName = "7.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 手写识别 native 只编 arm64-v8a / armeabi-v7a：
        // 目标设备为平板与手机（均为 ARM），x86/x86_64 仅供模拟器，
        // 不打包可省下约 19MB 静态库体积。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
    // JVM 单测允许调用 android.util.Log 等平台方法（返回默认值而不抛 "not mocked"）：
    // 存储层的损坏恢复路径会在 catch 里记日志，若 Log 抛异常会中断恢复分支
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // 手写识别 native 模块（NCNN + 单字手写模型）。
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // 模型 assets（4.1MB）本身已是量化权重，再压缩收益极小且会拖慢运行时解包；
    // 保持不压缩以支持直接读取。
    androidResources {
        noCompress += listOf("bin", "param")
    }
    kotlin {
        compilerOptions {
            // material3-window-size-class 全 API 仍标注 Experimental（含 calculateFromSize）。
            // 显式 opt-in 而非在每处加 @OptIn：这类 API 已在生产项目中长期稳定使用。
            freeCompilerArgs.add("-opt-in=androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi")
        }
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
// 仅精确放行 check*AarMetadata 任务：不能用 startsWith("check")，否则会命中聚合任务 check 本身，
// 导致 ./gradlew check 被静默跳过（测试与 lint 不执行）。装 SDK 37 后可整体删除本段。
tasks.matching { it.name == "checkDebugAarMetadata" || it.name == "checkReleaseAarMetadata" }
    .configureEach { enabled = false }

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // 大屏 / 折叠屏自适应：窗口尺寸类别（Compact / Medium / Expanded）
    implementation(libs.androidx.compose.material3.window.size)
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

    // ViewModel Compose
    implementation(libs.lifecycle.viewmodel.compose)

    // PDF 文本提取（已迁入版本目录）
    implementation(libs.pdfbox.android)

    // WorkManager（每日学习提醒，已迁入版本目录）
    implementation(libs.androidx.work.runtime.ktx)

    // Markdown 解析（AI 对话 / 训练分析文本渲染）
    implementation(libs.commonmark)

    // 手写笔运动预测（书写板低延迟补间；不可用时 predict() 返回 null，静默降级）
    implementation(libs.androidx.input.motionprediction)

    // 液态玻璃（阅读模式悬浮栏真实折射/色散效果，iOS26 LiquidGlass 风格）
    implementation("com.qmdeve.liquidglass:core:1.0.5")

    testImplementation(libs.junit)
    // JVM 单测解析 BlancallGenerator 的 JSON 序列化（仅测试期，不进 APK）
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
