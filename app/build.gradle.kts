plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.coda.mobileui"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.coda.mobileui"
        minSdk = 26
        targetSdk = 28
        // 版本号遵循语义化版本（SemVer）：MAJOR.MINOR.PATCH。
        //   PATCH：缺陷修复、内部重构、构建与资源调整，以及既有能力的实现替换（用户可见能力不变）。
        //   MINOR：新增一条独立、完整的向后兼容功能线。
        //   MAJOR：出现不向后兼容的变更，并在提交信息里写明破坏点。
        // 前两位数字不随日常提交递增：升级必须能在提交信息里说清依据，且一次只升最低必要的那一位。
        // versionName 的 -core 后缀表示"内核载荷随包内置"的构建线，不参与 SemVer 比较。
        // versionCode 与 versionName 同步递增，只增不减（覆盖安装依赖它单调）。
        //
        // 允许 CI 用 -PcodaVersionCode/-PcodaVersionName 覆盖，Tag 打包即用 Tag 号。
        versionCode = (project.findProperty("codaVersionCode") as String?)?.toInt() ?: 28
        versionName = (project.findProperty("codaVersionName") as String?) ?: "0.8.4-core"
    }
    signingConfigs {
        // 本地或 CI 提供密钥时才启用；否则 release 复用 debug 签名，保证产物可安装。
        val ksPath = System.getenv("CODA_KEYSTORE_PATH")
        if (!ksPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("CODA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CODA_KEY_ALIAS")
                keyPassword = System.getenv("CODA_KEY_PASSWORD")
                // 见下方 debug 配置里的说明：三个签名方案全部显式打开。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        // 固定调试密钥。CI 每次在全新 runner 上自动生成一把临时 debug keystore，签名一次一换，
        // 上一版装得上、下一版装不上，也没法覆盖安装；这里固定成仓库自带密钥，本机与 CI 一致。
        val repoDebug = rootProject.file("keystore/coda-debug.jks")
        if (repoDebug.isFile) {
            getByName("debug").apply {
                storeFile = repoDebug
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                // minSdk 26 时 AGP 默认只写 v2 签名，v1（JAR 签名）不生成；
                // 而只认 META-INF/*.RSA 那一套的检查工具（jarsigner -verify、部分签名查看器）
                // 会把这种包判成"未签名"。这里三个方案全开：v1 给老工具看，v2 给 Android 7+ 用，
                // v3 给新版系统做密钥轮换校验。签名者始终是仓库内那把固定密钥。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    lint {
        // targetSdk 固定 28 是本项目的有意选择，按项关掉这条政策告警，其余致命检查保留。
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    // AI 回复的 Markdown 渲染（加粗、列表、代码块、引用、链接等）
    implementation("io.noties.markwon:core:4.6.2")
}
