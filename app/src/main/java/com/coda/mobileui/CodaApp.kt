package com.coda.mobileui
import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.coda.mobileui.core.AppLog
import com.coda.mobileui.core.Bg
import com.coda.mobileui.core.PhoneControl
import com.coda.mobileui.core.UpdateInstaller
import com.coda.mobileui.ext.Extensions

/**
 * 启动时应用用户选择的主题模式（浅色 / 深色 / 跟随系统）。
 * 动态取色与自定义主色由 BaseActivity 按设置开关处理。
 */
class CodaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 日志要最先初始化：后面的每一步都可能在日志里留下唯一的线索
        AppLog.init(this)
        AppCompatDelegate.setDefaultNightMode(SettingsStore.get(this).nightMode)
        // 崩溃拦截：不闪退，展示崩溃页面并落盘日志
        CrashGuard.install(this)
        // 手机控制：开关开启时恢复本地 MCP 服务器（供内核连接）
        try {
            PhoneControl.startIfEnabled(this)
        } catch (_: Throwable) {
        }
        // 拓展：登记已开放分类并完成首次装载；内置拓展的二进制在这一步释放到运行目录
        try {
            Extensions.engine(this)
        } catch (_: Throwable) {
        }
        // 更新包清理：安装成功后旧包就没用了，但它有几十 MB。
        // 放到后台是因为要遍历目录，启动路径上不该做文件 IO。
        Bg.run {
            try {
                UpdateInstaller.cleanup(this@CodaApp, BuildConfig.VERSION_CODE)
            } catch (_: Throwable) {
            }
        }
    }
}
