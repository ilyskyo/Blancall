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
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.ilyskyo.blancall"
        minSdk = 26
        targetSdk = 37
        versionCode = 52
        versionName = "7.5.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 16 KB page size：NDK r27 需显式开启，否则 targetSdk 37 在 16KB 设备上
        // 可能无法加载 native 库（AGP 8.5+ 会校验 APK 对齐）。
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }

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
    bundle {
        language {
            // 界面语言由 AppLocale 在应用内切换。Play 按语言拆包会把非基础语种的资源
            // 从基础 APK 剥离，用户在应用内切到英/日/韩/俄/繁中时只会回落成简体中文。
            // APK 分发不受影响；这里关掉语言拆分，保证六语资源始终随包完整下发。
            enableSplit = false
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
                "com/tom_roush/pdfbox/resources/afm/*.jar",
                // pdfbox-android 传递引入的 BouncyCastle 后量子参数表（picnic + sike 四张），
                // 打进 APK 共 4,138,070 B；本工程无任何 BC 引用、dex 里也没有 BC 类，纯死重。
                // x509/CertPathReviewerMessages* 不在此列：那是 BC 运行时按名查找的资源，
                // 只有 12.8 KB，去掉它换来的行为风险不值。
                "org/bouncycastle/pqc/**"
            )
        }
    }
}

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
    implementation(libs.androidx.glance.appwidget)

    // Markdown 解析（AI 对话 / 训练分析文本渲染）
    implementation(libs.commonmark)

    // 手写笔运动预测（书写板低延迟补间；不可用时 predict() 返回 null，静默降级）
    implementation(libs.androidx.input.motionprediction)

    // 液态玻璃（阅读模式悬浮栏真实折射/色散效果，iOS26 LiquidGlass 风格）
    // 上游 com.qmdeve.liquidglass:core:1.0.5 已改为源码入库（见 docs/VENDOR-AndroidLiquidGlassView.md），
    // 这里只留它真正需要的那一个运行时依赖。
    implementation(libs.androidx.dynamicanimation)

    // 底部导航栏液态玻璃：Kyant0 Backdrop（Compose 原生 LayerBackdrop + RuntimeShader）
    implementation(libs.backdrop)

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
