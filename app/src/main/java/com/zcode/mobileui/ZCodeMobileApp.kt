package com.zcode.mobileui

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * 启动时应用用户选择的主题模式（浅色 / 深色 / 跟随系统）。
 * 动态取色与自定义主色由 BaseActivity 按设置开关处理。
 */
class ZCodeMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(SettingsStore.get(this).nightMode)
        // 崩溃拦截：不闪退，展示崩溃页面并落盘日志
        CrashGuard.install(this)
    }
}