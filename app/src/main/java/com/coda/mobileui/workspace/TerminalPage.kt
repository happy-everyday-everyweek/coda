package com.coda.mobileui.workspace

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coda.mobileui.R
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * 终端页：一个标签对应一个会话窗口。
 *
 * 容器里没有伪终端分配工具，所以这里跑的是常驻 shell，输入直接写入它的标准输入，
 * 输出按行汇总后渲染到等宽文本区。
 */
class TerminalPage(
    host: WorkspaceActivity,
    private val saved: JSONObject,
) : WorkspacePage(host) {

    override val kind: String = WorkspaceTabs.TERMINAL
    override val title: String = "终端"

    private lateinit var content: LinearLayout
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var statusLabel: TextView
    private lateinit var ctrlKey: TextView

    private val main = Handler(Looper.getMainLooper())
    private val lines = StringBuilder()
    private val pending = StringBuilder()

    private var process: Process? = null
    private var writer: OutputStream? = null
    private var started = false
    private var refreshScheduled = false
    private var ctrlArmed = false

    override fun build(): View {
        content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        content.addView(headerRow())
        content.addView(transcriptArea())
        content.addView(keyRow())
        content.addView(inputRow())
        return content
    }

    override fun onShown() {
        start()
    }

    override fun onClosed() {
        stop()
    }

    override fun subtitle(): String? = saveState().optString("cwd").ifEmpty { null }

    override fun saveState(): JSONObject = JSONObject()
        .put("cwd", runCatching { host.env.guestPath(host.root.absolutePath) }.getOrDefault(""))

    override fun menuItems(): List<String> = listOf("清屏", "重开会话", "发送中断")

    override fun onMenuItem(label: String) {
        when (label) {
            "清屏" -> clear()
            "重开会话" -> restart()
            "发送中断" -> writeRaw("\u0003")
        }
    }

    // ---------------------------------------------------------------- 视图

    private fun headerRow(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(12f), dp(8f))
        }
        val texts = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        statusLabel = label("未启动", 13f, onSurface).apply { maxLines = 1 }
        texts.addView(statusLabel)
        texts.addView(label(host.root.absolutePath, 11f, onSurfaceVariant).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(2f), 0, 0)
        })
        row.addView(texts)
        row.addView(pill("清屏") { clear() })
        row.addView(pill("重开") { restart() })
        return row
    }

    private fun transcriptArea(): View {
        transcript = TextView(host).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(onSurface)
            setTextIsSelectable(true)
            setPadding(dp(14f), dp(6f), dp(14f), dp(10f))
        }
        scroll = ScrollView(host).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(transcript, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        return scroll
    }

    private fun keyRow(): View {
        val strip = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val holder = HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            addView(strip, ViewGroup.LayoutParams(WRAP, WRAP))
        }
        strip.addView(key("CTRL", true) { toggleCtrl() })
        strip.addView(key("TAB") { writeRaw("\t") })
        strip.addView(key("ESC") { writeRaw("\u001b") })
        strip.addView(key("↑") { writeRaw("\u001b[A") })
        strip.addView(key("↓") { writeRaw("\u001b[B") })
        strip.addView(key("←") { writeRaw("\u001b[D") })
        strip.addView(key("→") { writeRaw("\u001b[C") })
        strip.addView(key("^C") { writeRaw("\u0003") })
        return holder
    }

    private fun inputRow(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), dp(10f))
        }
        input = EditText(host).apply {
            hint = "输入命令"
            typeface = android.graphics.Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_tool_card)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8f) }
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                    submit()
                    true
                } else {
                    false
                }
            }
        }
        row.addView(input)
        row.addView(pill("运行") { submit() })
        return row
    }

    private fun pill(text: String, click: () -> Unit): TextView = label(text, 13f, onSurfaceVariant).apply {
        setTextColor(onSurfaceVariant)
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_tool_card)
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8f) }
        isClickable = true
        setOnClickListener { click() }
    }

    private fun key(text: String, accent: Boolean = false, click: () -> Unit): TextView {
        val view = label(text, 12f, onSurfaceVariant).apply {
            setTextColor(onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_tool_card)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6f) }
            isClickable = true
            setOnClickListener { click() }
        }
        if (accent) ctrlKey = view
        return view
    }

    // ---------------------------------------------------------------- 会话

    private fun start() {
        if (started) return
        started = true
        statusLabel.text = "正在启动会话"
        host.ensureEnv { ready ->
            if (!ready) {
                statusLabel.text = "环境未就绪"
                appendNote("环境未就绪，无法启动会话。")
                return@ensureEnv
            }
            host.background(
                work = {
                    val builder = host.env.builder(
                        listOf("/bin/bash", "--norc", "-i"),
                        mapOf(
                            "TERM" to "dumb",
                            "PS1" to "\\w \\$ ",
                            "NO_COLOR" to "1",
                        ),
                    )
                    builder.redirectErrorStream(true)
                    builder.start()
                },
                then = { session -> attach(session) },
                onError = {
                    statusLabel.text = "会话启动失败"
                    appendNote("会话启动失败。")
                },
            )
        }
    }

    private fun attach(process: Process) {
        this.process = process
        writer = process.outputStream
        statusLabel.text = "会话运行中"
        appendNote("会话已启动：" + host.root.absolutePath)
        primeDirectory()
        val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
        thread(name = "coda-terminal-reader", isDaemon = true) {
            val buffer = CharArray(2048)
            try {
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    val chunk = String(buffer, 0, read)
                    main.post { appendChunk(chunk) }
                }
            } catch (_: Throwable) {
            }
            main.post {
                statusLabel.text = "会话已结束"
                appendNote("会话已结束。")
                this.process = null
                writer = null
            }
        }
    }

    private fun primeDirectory() {
        val guest = runCatching { host.env.guestPath(host.root.absolutePath) }.getOrDefault("/")
        val escaped = guest.replace("'", "'\\''")
        write("cd '" + escaped + "'")
    }

    private fun stop() {
        started = false
        val alive = process ?: return
        runCatching { alive.destroy() }
        process = null
        writer = null
    }

    private fun restart() {
        stop()
        clear()
        start()
    }

    private fun clear() {
        lines.setLength(0)
        pending.setLength(0)
        render()
    }

    private fun submit() {
        val text = input.text.toString()
        if (text.isEmpty()) {
            write("")
            return
        }
        if (ctrlArmed) {
            toggleCtrl()
            writeRaw(controlCode(text.first()).toString())
            input.setText("")
            return
        }
        input.setText("")
        write(text)
    }

    private fun toggleCtrl() {
        ctrlArmed = !ctrlArmed
        if (::ctrlKey.isInitialized) {
            ctrlKey.setTextColor(if (ctrlArmed) primary else onSurfaceVariant)
        }
    }

    private fun controlCode(char: Char): Char = when (char.uppercaseChar()) {
        in 'A'..'Z' -> (char.uppercaseChar().code - 'A'.code + 1).toChar()
        else -> char
    }

    /** 发送一行命令，并在本地回显一行提示。 */
    private fun write(command: String) {
        val stream = writer
        if (stream == null) {
            appendNote("会话未启动。")
            return
        }
        lines.append(PROMPT).append(command).append('\n')
        render()
        try {
            stream.write((command + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
        } catch (_: Throwable) {
            appendNote("写入失败，会话可能已经结束。")
        }
    }

    private fun writeRaw(text: String) {
        val stream = writer ?: run {
            appendNote("会话未启动。")
            return
        }
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
        } catch (_: Throwable) {
            appendNote("写入失败，会话可能已经结束。")
        }
    }

    // ---------------------------------------------------------------- 输出

    private fun appendChunk(chunk: String) {
        pending.append(chunk)
        var index = pending.indexOf("\n")
        while (index >= 0) {
            val line = pending.substring(0, index)
            pending.delete(0, index + 1)
            if (!noise(line)) lines.append(line).append('\n')
            index = pending.indexOf("\n")
        }
        schedule()
    }

    private fun appendNote(text: String) {
        lines.append(text).append('\n')
        schedule()
    }

    /** 无伪终端时 shell 会打出的启动告警，不展示。 */
    private fun noise(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return false
        return text.contains("job control") ||
            text.contains("terminal process group") ||
            text.contains("can't access tty") ||
            text.contains("Inappropriate ioctl")
    }

    private fun schedule() {
        if (refreshScheduled) return
        refreshScheduled = true
        main.postDelayed({
            refreshScheduled = false
            render()
        }, 50)
    }

    private fun render() {
        if (!::transcript.isInitialized) return
        if (lines.length > MAX_CHARS) {
            lines.delete(0, lines.length - MAX_CHARS)
            lines.insert(0, "…\n")
        }
        val folded = if (pending.isEmpty()) {
            lines.toString()
        } else {
            lines.toString() + pending.toString()
        }
        transcript.text = folded
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        const val PROMPT = "› "
        const val MAX_CHARS = 120_000
    }
}