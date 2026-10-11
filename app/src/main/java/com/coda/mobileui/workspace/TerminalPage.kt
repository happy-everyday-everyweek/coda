package com.coda.mobileui.workspace

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.coda.mobileui.R
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * 终端标签页。
 *
 * 界面按 Termux 的口径组织：整屏终端区加底部一行附加键区，不自建标题栏。
 * 容器里没有伪终端，会话是常驻 shell，输入直接写入它的标准输入，输出按行渲染。
 * 多会话沿用容器底部标签栏，本页不再自建标签条，避免与底部工具条重叠或抢手势。
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
    private lateinit var status: TextView
    private lateinit var input: TerminalInput

    private val main = Handler(Looper.getMainLooper())
    private val lines = StringBuilder()
    private val pending = StringBuilder()
    private val echo = StringBuilder()
    private val modifierKeys = mutableListOf<Pair<TextView, String>>()

    private var process: Process? = null
    private var writer: OutputStream? = null
    private var started = false
    private var refreshScheduled = false
    private var follow = true
    private var ctrlArmed = false
    private var altArmed = false
    private var keyboardShown = false
    private var keyDownY = 0f

    override fun build(): View {
        content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        content.addView(terminalArea(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        content.addView(keyArea())
        content.viewTreeObserver.addOnGlobalLayoutListener { syncBottomInset() }
        syncBottomInset()
        return content
    }

    /** 底部工具条是浮层，这里留出它的高度，附加键区才不会被压住。 */
    private fun syncBottomInset() {
        if (!::content.isInitialized) return
        val padding = host.bottomBarHeight()
        if (content.paddingBottom != padding) content.setPadding(0, 0, 0, padding)
    }

    override fun onShown() {
        syncBottomInset()
        start()
    }

    override fun onClosed() {
        stop()
    }

    override fun subtitle(): String? = runCatching { host.env.guestPath(host.root.absolutePath) }.getOrNull()

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

    private fun terminalArea(): View {
        transcript = TextView(host).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(onSurface)
            setTextIsSelectable(true)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnClickListener { toggleKeyboard() }
        }
        scroll = ScrollView(host).apply {
            isFillViewport = true
            addView(transcript, ViewGroup.LayoutParams(MATCH, WRAP))
            setOnScrollChangeListener { _, _, _, _, _ -> follow = atBottom() }
        }
        input = TerminalInput(host).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                keyboardShown = insets.isVisible(WindowInsetsCompat.Type.ime())
                insets
            }
        }
        status = label("正在启动会话", 12.5f, onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = ContextCompat.getDrawable(host, R.drawable.bg_tool_card)
        }
        val area = FrameLayout(host)
        area.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        area.addView(input, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        area.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return area
    }

    /** Termux 的默认键位：上一行功能键，下一行修饰键与方向键。 */
    private fun keyArea(): View {
        val bar = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(4f), dp(4f), dp(4f), dp(6f))
        }
        bar.addView(keyRow(firstRow))
        bar.addView(keyRow(secondRow))
        return bar
    }

    private fun keyRow(keys: List<KeySpec>): View {
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        for (spec in keys) {
            row.addView(keyCell(spec), LinearLayout.LayoutParams(0, dp(36f), 1f))
        }
        return row
    }

    private fun keyCell(spec: KeySpec): TextView {
        val cell = TextView(host).apply {
            text = spec.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(onSurfaceVariant)
            gravity = Gravity.CENTER
            isClickable = true
        }
        when (spec.special) {
            "ctrl" -> {
                modifierKeys += cell to "ctrl"
                cell.setOnClickListener { toggleModifier("ctrl") }
            }

            "alt" -> {
                modifierKeys += cell to "alt"
                cell.setOnClickListener { toggleModifier("alt") }
            }

            else -> {
                cell.setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            keyDownY = event.y
                            false
                        }

                        MotionEvent.ACTION_UP -> {
                            view.performClick()
                            val popup = spec.popup
                            if (popup != null && keyDownY - event.y > dp(18f).toFloat()) sendKey(popup) else sendKey(spec.bytes)
                            true
                        }

                        else -> false
                    }
                }
            }
        }
        return cell
    }

    private fun paintModifiers() {
        for ((cell, name) in modifierKeys) {
            val armed = if (name == "ctrl") ctrlArmed else altArmed
            cell.setTextColor(if (armed) primary else onSurfaceVariant)
        }
    }

    private fun toggleModifier(name: String) {
        if (name == "ctrl") ctrlArmed = !ctrlArmed else altArmed = !altArmed
        paintModifiers()
    }

    private fun setModifiers(ctrl: Boolean, alt: Boolean) {
        ctrlArmed = ctrl
        altArmed = alt
        paintModifiers()
    }

    /** 附加键发出的内容：单字符在 CTRL 生效时转成控制码，ALT 生效时前置 ESC。 */
    private fun sendKey(bytes: String) {
        var out = bytes
        if (ctrlArmed && out.length == 1) out = controlCode(out[0]).toString()
        if (altArmed) out = "\u001b$out"
        writeRaw(out)
        if (ctrlArmed || altArmed) setModifiers(false, false)
    }

    // ---------------------------------------------------------------- 输入

    /** 点终端区弹出或收起软键盘，按键直接进会话，不再有独立输入框。 */
    private fun toggleKeyboard() {
        val manager = host.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (keyboardShown) {
            manager.hideSoftInputFromWindow(input.windowToken, 0)
            keyboardShown = false
        } else {
            input.requestFocus()
            manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            keyboardShown = true
        }
    }

    /** 终端区的 IME 目标：把键盘输入按字节送进 shell。 */
    private inner class TerminalInput(context: Context) : View(context) {

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_NULL
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
            return object : BaseInputConnection(this@TerminalInput, true) {

                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    if (!text.isNullOrEmpty()) typeText(text.toString())
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength) { typeText("\u007f") }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action != KeyEvent.ACTION_DOWN) return true
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> submitLine()
                        KeyEvent.KEYCODE_DEL -> typeText("\u007f")
                        else -> {
                            val code = event.unicodeChar
                            if (code != 0) {
                                val char = code.toChar()
                                val ctrl = event.isCtrlPressed || ctrlArmed
                                typeText(if (ctrl) controlCode(char).toString() else char.toString())
                            }
                        }
                    }
                    return true
                }
            }
        }
    }

    /** 键入字符：写进会话，同时在本地回显，没有伪终端时才看得到自己打了什么。 */
    private fun typeText(text: String) {
        for (char in text) {
            when (char) {
                '\u007f' -> {
                    if (echo.isNotEmpty()) {
                        echo.deleteCharAt(echo.length - 1)
                        writeRaw("\u007f")
                    }
                }

                '\n', '\r' -> submitLine()
                else -> {
                    echo.append(char)
                    writeRaw(char.toString())
                }
            }
        }
        render()
    }

    private fun submitLine() {
        lines.append(PROMPT).append(echo).append('\n')
        echo.setLength(0)
        writeRaw("\n")
        render()
    }

    private fun controlCode(char: Char): Char = when (char.uppercaseChar()) {
        in 'A'..'Z' -> (char.uppercaseChar().code - 'A'.code + 1).toChar()
        ' ' -> '\u0000'
        else -> char
    }

    // ---------------------------------------------------------------- 会话

    private fun start() {
        if (started) return
        started = true
        status.text = "正在启动会话"
        status.visibility = View.VISIBLE
        host.ensureEnv { ready ->
            if (!ready) {
                status.text = "环境未就绪"
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
                    status.text = "会话启动失败"
                    appendNote("会话启动失败。")
                },
            )
        }
    }

    private fun attach(session: Process) {
        process = session
        writer = session.outputStream
        status.visibility = View.GONE
        appendNote("会话已启动：" + host.root.absolutePath)
        primeDirectory()
        val reader = InputStreamReader(session.inputStream, Charsets.UTF_8)
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
                appendNote("会话已结束。")
                status.text = "会话已结束"
                status.visibility = View.VISIBLE
                process = null
                writer = null
            }
        }
    }

    private fun primeDirectory() {
        val guest = runCatching { host.env.guestPath(host.root.absolutePath) }.getOrDefault("/")
        writeRaw("cd '" + guest.replace("'", "'\\''") + "'\n")
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
        echo.setLength(0)
        render()
    }

    private fun writeRaw(text: String) {
        val stream = writer ?: return
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
            val lineText = pending.substring(0, index)
            pending.delete(0, index + 1)
            if (!noise(lineText)) lines.append(lineText).append('\n')
            index = pending.indexOf("\n")
        }
        schedule()
    }

    private fun appendNote(text: String) {
        lines.append(text).append('\n')
        schedule()
    }

    /** 无伪终端时 shell 会打出的启动告警，不展示。 */
    private fun noise(lineText: String): Boolean {
        val text = lineText.trim()
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

    private fun atBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= child.height - dp(8f)
    }

    private fun render() {
        if (!::transcript.isInitialized) return
        if (lines.length > MAX_CHARS) {
            lines.delete(0, lines.length - MAX_CHARS)
            lines.insert(0, "…\n")
        }
        val tail = buildString {
            append(pending)
            if (echo.isNotEmpty()) append(PROMPT).append(echo)
        }
        transcript.text = lines.toString() + tail
        if (follow) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private class KeySpec(
        val label: String,
        val bytes: String,
        val popup: String? = null,
        val special: String? = null,
    )

    private companion object {
        const val PROMPT = "\u203a "
        const val MAX_CHARS = 120_000

        val firstRow = listOf(
            KeySpec("ESC", "\u001b"),
            KeySpec("/", "/"),
            KeySpec("-", "-", "|"),
            KeySpec("HOME", "\u001b[H"),
            KeySpec("\u2191", "\u001b[A"),
            KeySpec("END", "\u001b[F"),
            KeySpec("PGUP", "\u001b[5~"),
        )

        val secondRow = listOf(
            KeySpec("TAB", "\t"),
            KeySpec("CTRL", "", special = "ctrl"),
            KeySpec("ALT", "", special = "alt"),
            KeySpec("\u2190", "\u001b[D"),
            KeySpec("\u2193", "\u001b[B"),
            KeySpec("\u2192", "\u001b[C"),
            KeySpec("PGDN", "\u001b[6~"),
        )
    }
}
