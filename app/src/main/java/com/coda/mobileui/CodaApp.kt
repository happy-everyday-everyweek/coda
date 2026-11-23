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
        // 崩溃拦截必须排在所有设置读取之前：SharedPreferences 损坏这类早期失败
        // 也要能落到崩溃页与日志里，否则启动阶段的问题永远查不到
        CrashGuard.install(this)
        // 进程守卫（#818）：CrashActivity 跑在 :crash 进程，onCreate 每个进程
        // 都会走。MCP 服务器、扩展二进制释放、更新清理都只能主进程做——
        // :crash 里再跑一遍会端口冲突、与主进程并发写同一运行目录。
        if (!isMainProcess()) return
        AppCompatDelegate.setDefaultNightMode(readNightMode())
        // 手机控制：开关开启时恢复本地 MCP 服务器（供内核连接）
        try {
            PhoneControl.startIfEnabled(this)
        } catch (e: Exception) {
            // 只吞可恢复异常，并留下线索；OOM、栈溢出这类 Error 交给崩溃防护
            AppLog.e(TAG, "手机控制启动失败", e)
        }
        Bg.run {
            // 拓展：登记分类并完成首次装载。内置拓展的二进制在这一步释放到运行目录，
            // 是实打实的文件 IO，不能挂在 onCreate 上
            try {
                Extensions.engine(applicationContext)
            } catch (e: Exception) {
                AppLog.e(TAG, "扩展装载失败，本次会话扩展不可用", e)
            }
            // 更新包清理：安装成功后旧包就没用了，但它有几十 MB
            try {
                UpdateInstaller.cleanup(applicationContext, BuildConfig.VERSION_CODE)
            } catch (e: Exception) {
                AppLog.e(TAG, "更新包清理失败", e)
            }
        }
    }

    /**
     * 是否主进程。API 28+ 用 Application.getProcessName；更低版本读
     * /proc/self/cmdline（主进程名就是包名，子进程带 :suffix）。
     * 读不出时按主进程处理：宁可多初始化，不能让主进程漏掉 MCP/扩展。
     */
    private fun isMainProcess(): Boolean {
        val name =
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                getProcessName()
            } else {
                runCatching {
                    java.io
                        .File("/proc/self/cmdline")
                        .readText()
                        .trim('\u0000', ' ', '\n')
                }.getOrNull()
            }
        return name == null || name == packageName
    }

    /**
     * 读取夜间模式设置。
     *
     * 设置存储可能因为文件损坏、读到半个 JSON 等原因在启动时抛异常。
     * 这里退回「跟随系统」——对它来说任何取值都能用，不至于让应用起不来。
     */
    private fun readNightMode(): Int =
        try {
            SettingsStore.get(this).nightMode
        } catch (e: Exception) {
            AppLog.e(TAG, "读取夜间模式失败，本次跟随系统", e)
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

    private companion object {
        const val TAG = "CodaApp"
    }
}
