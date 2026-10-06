package com.zcode.mobileui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    @Volatile
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveAndShow(app, thread, throwable)
            } catch (_: Throwable) {
            }
            // 主进程稍后会被结束，让位给崩溃页；这里不再把异常继续抛给系统默认处理器。
            if (prev != null) {
                try {
                    prev.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun saveAndShow(app: Application, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val body = buildString {
            append("时间: ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append('\n')
            append("线程: ").append(thread.name).append('\n')
            append("异常: ").append(throwable.toString()).append("\n\n")
            append(sw.toString())
        }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var saved = "(未写入)"
        try {
            val dir = app.getExternalMediaDirs()?.firstOrNull()?.let { File(it, "logs") }
                ?: File(app.filesDir, "run-logs")
            dir.mkdirs()
            val f = File(dir, "crash-$ts.txt")
            f.writeText(body)
            saved = f.absolutePath
        } catch (_: Throwable) {
        }
        val intent = Intent(app, CrashActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        intent.putExtra(CrashActivity.EXTRA_LOG, body)
        intent.putExtra(CrashActivity.EXTRA_PATH, saved)
        try {
            app.startActivity(intent)
        } catch (_: Throwable) {
        }
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                Process.killProcess(Process.myPid())
            } catch (_: Throwable) {
            }
        }, 700)
    }
}

/** 崩溃展示页（独立进程）。 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = intent.getStringExtra(EXTRA_LOG) ?: "(无日志)"
        val path = intent.getStringExtra(EXTRA_PATH) ?: "(未保存)"
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "应用发生崩溃"
            textSize = 20f
        }
        val pathView = TextView(this).apply {
            text = "崩溃日志: $path"
            textSize = 12f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val scroll = ScrollView(this)
        val content = TextView(this).apply {
            text = log
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val btn = Button(this).apply {
            text = "关闭"
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
    }
}