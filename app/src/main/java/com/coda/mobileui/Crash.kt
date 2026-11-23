package com.coda.mobileui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coda.mobileui.core.AppLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃拦截：不闪退——落盘日志并展示崩溃页面（独立 :crash 进程，主进程结束也不影响展示）。
 */
object CrashGuard {
    /** 检查与置位必须原子：并发 install 时两个线程都过 if 会重复包装处理器链。 */
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveAndShow(app, thread, throwable)
            } catch (_: Throwable) {
            }
            // 不再转发给系统默认处理器：它会在崩溃页展示前就把进程杀掉，
            // 延迟 kill 与自绘崩溃页都会失效（用户只看到闪退）。
        }
    }

    /** Intent 里的日志摘要上限：远低于 Binder 1MB 事务限制，字符数按 UTF-16 计。 */
    internal const val INTENT_LOG_LIMIT = 64 * 1024

    /** 超长正文只保留尾部（堆栈在末尾，最有诊断价值），并标注全文位置提示。 */
    internal fun tailForIntent(body: String): String =
        if (body.length <= INTENT_LOG_LIMIT) {
            body
        } else {
            "(日志过长，以下为尾部摘要，全文见崩溃日志文件)\n" + body.takeLast(INTENT_LOG_LIMIT)
        }

    private fun saveAndShow(
        app: Application,
        thread: Thread,
        throwable: Throwable,
    ) {
        // 先挂一个**兜底**看门狗，再做落盘与展示（#737）。
        //
        // 之前把 kill 投递到主 Looper：崩溃发生在主线程时 Looper 已经退出，
        // 那条 postDelayed 永远不会执行；而转发系统处理器又已被移除，
        // 结果是进程僵死——没有 UI 线程、不重启、也不弹系统崩溃框。
        //
        // 兜底期限给得比正常宽限期长得多：外部媒体目录可能是慢速 FUSE，
        // 写日志 + 起 Activity 超过 700ms 很常见，用 700ms 兜底会在写一半时
        // 把进程杀掉（崩溃文件截断、崩溃页根本没起来）。正常路径走完全流程后
        // 另起一次 700ms 的 scheduleKill，谁先到谁生效。
        scheduleKill(WATCHDOG_MS)
        // PrintWriter 用 use 包裹（#646）：崩溃路径本身也可能再抛异常，
        // 不关闭会漏掉 flush，堆栈尾部可能缺失。
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        // 崩溃前的最后若干行日志是找根因的关键：只有堆栈时，原因往往在上一屏，
        // 而 logcat 在用户设备上拿不到，崩溃一过就被刷掉。
        val before = AppLog.recentText(200)
        val body =
            buildString {
                append("时间: ")
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                append('\n')
                append("线程: ").append(thread.name).append('\n')
                append("异常: ").append(throwable.toString()).append("\n\n")
                append("===== 崩溃前日志 =====\n")
                if (before.isEmpty()) {
                    append("(无运行期日志)\n")
                } else {
                    append(before).append('\n')
                }
                append("\n===== 堆栈 =====\n")
                append(sw.toString())
            }
        // 同时写进运行日志：崩溃之后用户翻日志也能直接看到这一段，不必去找崩溃文件。
        AppLog.e("Crash", "未捕获异常（${thread.name}）：$throwable", throwable)
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var saved = "(未写入)"
        // 外部媒体目录不可写（未挂载、权限被回收）时回退应用私有目录：
        // 只试一次外部路径会让崩溃日志彻底丢失，而这正是排查问题唯一的一手材料。
        val candidates =
            buildList {
                app.getExternalMediaDirs()?.firstOrNull()?.let { add(File(it, "logs")) }
                add(File(app.filesDir, "run-logs"))
            }
        for (dir in candidates) {
            val ok =
                try {
                    dir.mkdirs()
                    val f = File(dir, "crash-$ts.txt")
                    f.writeText(body)
                    saved = f.absolutePath
                    true
                } catch (_: Throwable) {
                    false
                }
            if (ok) break
        }
        val intent = Intent(app, CrashActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // Intent extras 走 Binder（约 1MB 事务上限）：完整日志可能超限导致
        // startActivity 抛 TransactionTooLargeException。只带尾部摘要，
        // 崩溃页按 EXTRA_PATH 从文件读全文（跨进程可读同一文件）。
        intent.putExtra(CrashActivity.EXTRA_LOG, tailForIntent(body))
        intent.putExtra(CrashActivity.EXTRA_PATH, saved)
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            // 崩溃页起不来必须留痕：这是最后一道可见性，不能再静默。
            AppLog.e("Crash", "崩溃页启动失败：${e.javaClass.simpleName}: ${e.message}")
        }
        // 落盘与展示都走完了，现在按正常宽限期结束进程；兜底看门狗仍在跑，
        // 谁先到谁生效（killProcess 幂等）。
        scheduleKill()
    }

    /** 给崩溃页留出的展示时间：它在 :crash 独立进程，杀主进程不影响它。 */
    internal const val KILL_DELAY_MS = 700L

    /**
     * 兜底看门狗期限。
     *
     * 覆盖「落盘 + 起 Activity」的最坏耗时（外部媒体目录可能是慢速 FUSE）。
     * 只有流程卡死或提前抛异常时它才生效，正常路径由 KILL_DELAY_MS 先结束进程。
     */
    internal const val WATCHDOG_MS = 8_000L

    /**
     * 在独立线程上延迟结束本进程（#737）。
     *
     * 不用主 Looper：崩溃可能就发生在主线程，那时 Looper 已退出，投递的任务不会执行。
     * 守护线程 + 捕获 InterruptedException，进程被别的路径先结束时也不会留下挂起线程。
     */
    internal fun scheduleKill(delayMs: Long = KILL_DELAY_MS) {
        try {
            Thread({
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    // 被唤醒也照样终止：这里的目的就是确保进程不会僵死。
                }
                try {
                    Process.killProcess(Process.myPid())
                } catch (_: Throwable) {
                }
            }, "coda-crash-kill").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
            // 崩溃本身就是 OOM 时（含 native pthread_create failed），连线程都起不来。
            // 这时立刻结束进程，宁可没有宽限期，也不能回到僵死状态。
            try {
                Process.killProcess(Process.myPid())
            } catch (_: Throwable) {
            }
        }
    }
}

/** 崩溃展示页（独立进程）。 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: getString(R.string.crash_not_saved)
        // 优先从文件读全文：Intent 只带尾部摘要（Binder 事务上限），
        // 文件读失败（未写入/被清理）才退回摘要。上限防异常大文件撑爆内存。
        val fallback = intent.getStringExtra(EXTRA_LOG) ?: getString(R.string.crash_no_log)
        val log =
            runCatching {
                val f = File(path)
                if (f.isFile && f.length() in 1..MAX_CRASH_FILE_BYTES) f.readText() else fallback
            }.getOrDefault(fallback)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        val title =
            TextView(this).apply {
                text = getString(R.string.crash_title)
                textSize = 20f
            }
        val pathView =
            TextView(this).apply {
                text = getString(R.string.crash_log_path, path)
                textSize = 12f
                setPadding(0, pad / 2, 0, pad / 2)
            }
        val scroll = ScrollView(this)
        val content =
            TextView(this).apply {
                text = log
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val btn =
            Button(this).apply {
                text = getString(R.string.crash_close)
                setOnClickListener { finish() }
            }
        root.addView(title)
        root.addView(pathView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btn)
        setContentView(root)
    }

    companion object {
        const val EXTRA_LOG = "log"
        const val EXTRA_PATH = "path"

        /** 崩溃文件读取上限：正常崩溃日志几十 KB，8MB 已极宽裕。 */
        private const val MAX_CRASH_FILE_BYTES = 8L * 1024 * 1024
    }
}
