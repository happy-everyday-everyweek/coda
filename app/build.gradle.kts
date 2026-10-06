plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zcode.mobileui"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zcode.mobileui"
        minSdk = 26
        targetSdk = 28
        versionCode = 2
        versionName = "0.2.0-core"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
