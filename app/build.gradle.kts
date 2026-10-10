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
        // 允许 CI 用 -PcodaVersionCode/-PcodaVersionName 覆盖，Tag 打包即用 Tag 号。
        versionCode = (project.findProperty("codaVersionCode") as String?)?.toInt() ?: 25
        versionName = (project.findProperty("codaVersionName") as String?) ?: "0.8.1-core"
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
