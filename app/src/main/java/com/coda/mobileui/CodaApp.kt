package com.coda.mobileui
import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.coda.mobileui.core.PhoneControl
/**
 * 启动时应用用户选择的主题模式（浅色 / 深色 / 跟随系统）。
 * 动态取色与自定义主色由 BaseActivity 按设置开关处理。
 */
class CodaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(SettingsStore.get(this).nightMode)
        // 崩溃拦截：不闪退，展示崩溃页面并落盘日志
        CrashGuard.install(this)
        // 手机控制：开关开启时恢复本地 MCP 服务器（供内核连接）
        try {
            PhoneControl.startIfEnabled(this)
        } catch (_: Throwable) {
        }
    }
}