package com.coda.mobileui.core

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动时的静默更新：查到新版本就在后台把包下好，不打扰用户。
 *
 * 为什么不直接弹卡片问：用户每次启动都会被问一遍「要不要更新」，而多数时候
 * 他正急着用应用。反过来，等用户主动去设置里点检查，又很可能已经过了很久。
 * 折中做法是——后台悄悄下好，等到合适的时候只需要用户点一下「安装」。
 *
 * 三条约束：
 *
 * 1. **同一进程只跑一次**。启动、切回前台、设置页恢复都可能触发检查，
 *    没有这道闸门就会出现多个下载线程抢同一个 .part 文件。
 * 2. **失败不重试、不提示**。静默路径的失败对用户没有意义（多半是弱网），
 *    下个版本再查即可；把错误弹出来反而制造焦虑。
 * 3. **不覆盖用户在下载的东西**。走的是 UpdateInstaller.download，它按
 *    `coda-<versionCode>.apk` 命名，与手动下载同版本时天然复用同一个文件。
 */
object SilentUpdater {

    /** 正在跑（或已跑过）的标记。跨启动不做持久化：每次冷启动都算新机会。 */
    private val started = AtomicBoolean(false)

    /** 最近一次后台下载完成的包，供界面判断「是不是已经下好了」。 */
    @Volatile
    var readyFile: File? = null
        private set

    @Volatile
    var readyResult: UpdateChecker.Result? = null
        private set

    /** 上一次静默流程的结论，用于排查（不上界面）。 */
    @Volatile
    var lastOutcome: String = ""
        private set

    /**
     * 查一次并在需要时下载。
     *
     * 同步执行，必须放后台线程。返回「已经下好的包」或 null。
     *
     * @param onReady 下载完成（或发现同版本已存在）时回调，主线程
     */
    fun checkAndFetch(
        ctx: Context,
        onReady: ((UpdateChecker.Result, File) -> Unit)? = null,
    ) {
        if (!started.compareAndSet(false, true)) {
            // 已经跑过：如果上次下好了，这次直接把它交出去，不重复下载。
            val r = readyResult
            val f = readyFile
            if (r != null && f != null && f.isFile) onReady?.invoke(r, f)
            return
        }

        if (!UpdateInstaller.isSupportedAbi()) {
            lastOutcome = "架构不支持，跳过"
            return
        }

        val r = try {
            UpdateChecker.check(ctx) { AppLog.d("SilentUpdater", it) }
        } catch (t: Throwable) {
            lastOutcome = "检查失败：${t.javaClass.simpleName}"
            return
        }
        if (!r.ok || !r.hasUpdate) {
            lastOutcome = if (r.ok) "已是最新" else "检查失败：${r.error}"
            return
        }

        AppLog.d("SilentUpdater", "发现新版本 ${r.versionCode}（${r.versionName}），开始后台下载")
        val file = try {
            UpdateInstaller.download(
                ctx,
                r,
                onLog = { AppLog.d("SilentUpdater", it) },
            )
        } catch (t: Throwable) {
            lastOutcome = "下载异常：${t.javaClass.simpleName}"
            return
        }
        if (file == null) {
            // 静默路径不提示失败：弱网下很常见，下个版本再查即可。
            lastOutcome = "下载失败"
            AppLog.d("SilentUpdater", "后台下载失败，本次不再重试")
            return
        }

        readyFile = file
        readyResult = r
        lastOutcome = "已就绪：${file.name}"
        AppLog.d("SilentUpdater", "后台下载完成：${file.absolutePath}")
        onReady?.invoke(r, file)
    }

    /**
     * 取出已经下好的包。
     *
     * 界面在「点版本号检查更新」时会先问这里：如果启动时已经悄悄下好了，
     * 就不必再走一遍查询与下载，直接给用户「立即安装」。
     */
    fun takeReady(): Pair<UpdateChecker.Result, File>? {
        val r = readyResult ?: return null
        val f = readyFile ?: return null
        if (!f.isFile || f.length() == 0L) return null
        // 边角情况：文件被系统清掉了（空间不足时 Android 会清 filesDir 缓存），
        // 那就当没有，让调用方走正常流程。
        return r to f
    }
}