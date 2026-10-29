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
    private const val TAG = "SilentUpdater"

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
            AppLog.i(TAG, "已在本次会话执行过，跳过；上次结果=$lastOutcome")
            if (r != null && f != null && f.isFile) onReady?.invoke(r, f)
            return
        }

        // 全链路用 INFO 而不是 DEBUG：AppLog.minLevel 默认就是 INFO，
        // 用 d() 写的行在正式包里会被直接丢弃，等于没埋点。
        val t0 = System.currentTimeMillis()
        AppLog.i(
            TAG,
            "开始静默更新检查 current=${UpdateChecker.currentVersionCode}" +
                "(${UpdateChecker.currentVersionName}) channel=${UpdateChecker.channel}",
        )

        if (!UpdateInstaller.isSupportedAbi()) {
            lastOutcome = "架构不支持，跳过"
            AppLog.w(TAG, "架构不支持，跳过（SUPPORTED_ABIS 无 arm64）")
            return
        }

        val r =
            try {
                UpdateChecker.check(ctx) { AppLog.i(TAG, it) }
            } catch (t: Throwable) {
                lastOutcome = "检查失败：${t.javaClass.simpleName}: ${t.message}"
                AppLog.e(TAG, "检查抛异常：$lastOutcome", t)
                return
            }
        val checkMs = System.currentTimeMillis() - t0
        if (!r.ok) {
            lastOutcome = "检查失败：${r.error}"
            AppLog.w(TAG, "检查失败（${checkMs}ms）：${r.error}")
            return
        }
        if (!r.hasUpdate) {
            lastOutcome = "已是最新"
            AppLog.i(TAG, "已是最新（${checkMs}ms）远端最高=${r.versionCode}")
            return
        }

        AppLog.i(
            TAG,
            "发现新版本 ${r.versionCode}（${r.versionName}）" +
                " 大小=${fmtSize(r.apkSize)} 渠道=${r.tag} 当前=${UpdateChecker.currentVersionCode}" +
                " sha256=${r.sha256?.take(12) ?: "无"}",
        )

        // 同版本已经下好时 download() 会直接复用，这里先探一下，
        // 免得日志里出现一段没有意义的「再下一次」。
        val t1 = System.currentTimeMillis()
        val file =
            try {
                UpdateInstaller.download(
                    ctx,
                    r,
                    onProgress = { done, total ->
                        // 进度回调太密，只在 25% 的整数倍上落日志，避免刷爆文件。
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p % 25 == 0 && p != lastPctLogged) {
                                lastPctLogged = p
                                AppLog.i(TAG, "下载进度 $p%（${fmtSize(done)}/${fmtSize(total)}）")
                            }
                        }
                    },
                    onLog = { AppLog.i(TAG, it) },
                )
            } catch (t: Throwable) {
                lastOutcome = "下载异常：${t.javaClass.simpleName}: ${t.message}"
                AppLog.e(TAG, "下载抛异常：$lastOutcome", t)
                return
            }
        val dlMs = System.currentTimeMillis() - t1
        if (file == null) {
            // 静默路径不提示失败：弱网下很常见，下个版本再查即可。
            lastOutcome = "下载失败（${dlMs}ms）"
            AppLog.w(TAG, "后台下载失败，本次不再重试。耗时 ${dlMs}ms")
            return
        }

        readyFile = file
        readyResult = r
        lastOutcome = "已就绪：${file.name}"
        AppLog.i(
            TAG,
            "后台下载完成 ${file.name} 体积=${fmtSize(file.length())} 耗时=${dlMs}ms" +
                " 总计=${System.currentTimeMillis() - t0}ms",
        )
        onReady?.invoke(r, file)
    }

    /** 上一次记过日志的进度百分比，防止 25% 那条重复写。 */
    @Volatile
    private var lastPctLogged = -1

    private fun fmtSize(bytes: Long): String =
        when {
            bytes <= 0 -> "未知"
            bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
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
        if (!f.isFile || f.length() == 0L) {
            AppLog.w(TAG, "已记录的包不存在了（可能被系统清理）：${f.absolutePath}")
            return null
        }
        AppLog.i(TAG, "复用已下载的包：${f.name}（${fmtSize(f.length())}）")
        return r to f
    }
}
