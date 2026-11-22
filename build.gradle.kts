plugins {
    id("com.android.application") version "8.13.2" apply false
    // KGP 2.2.x 官方支持 AGP 8.13（2.0.21 的已测上限远低于当前 AGP，会发警告甚至失败）。
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("com.chaquo.python") version "17.2.0" apply false
}
