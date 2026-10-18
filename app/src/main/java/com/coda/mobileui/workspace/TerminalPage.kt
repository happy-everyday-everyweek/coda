package com.coda.mobileui.workspace

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * 终端标签页。
 *
 * 界面按 Termux 的口径组织：黑底浅字、整屏终端区、底部一行附加键区，不自建标题栏。
 * 容器里没有伪终端，会话是常驻 shell，输入直接写入它的标准输入，输出按行渲染，
 * 并自绘一个闪烁块光标，因为管道没有 tty，系统不会给光标。
 * 多会话沿用容器原有标签页系统，本页不自建标签条。
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
    private var cursorOn = true
    private var keyDownY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L

    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            render()
            main.postDelayed(this, BLINK_MS)
        }
    }

    override fun build(): View {
        content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(terminalBackground)
        }
        content.addView(terminalArea(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        content.addView(keyArea())
        content.viewTreeObserver.addOnGlobalLayoutListener { syncBottomInset() }
        syncBottomInset()
        return content
    }

    /** 底部操作栏是浮层，这里留出它的高度，附加键区才不会被压住。 */
    private fun syncBottomInset() {
        if (!::content.isInitialized) return
        val padding = host.bottomBarHeight()
        if (content.paddingBottom != padding) content.setPadding(0, 0, 0, padding)
    }

    override fun onShown() {
        syncBottomInset()
        start()
        // 打开终端就直接把焦点与键盘备好，不必先点一下屏幕才开始能打字。
        input.post {
            refocusInput()
            val manager = host.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        main.removeCallbacks(blink)
        main.post(blink)
    }

    override fun onHidden() {
        main.removeCallbacks(blink)
    }

    override fun onClosed() {
        main.removeCallbacks(blink)
        stop()
    }

    override fun subtitle(): String? = runCatching { host.env.guestPath(host.root.absolutePath) }.getOrNull()

    override fun saveState(): JSONObject = JSONObject()
        .put("cwd", runCatching { host.env.guestPath(host.root.absolutePath) }.getOrDefault(""))

    override fun menuItems(): List<String> = listOf("清屏", "重开会话", "发送中断")

    override fun onMenuItem(label: String) {
        when (label) {
            "清屏" -> clear()
            "重开会话", "刷新" -> restart()
            "发送中断" -> writeRaw("\u0003")
        }
    }

    // ---------------------------------------------------------------- 视图

    private fun terminalArea(): View {
        transcript = TextView(host).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(terminalForeground)
            setBackgroundColor(terminalBackground)
            setTextIsSelectable(true)
            // 只保留长按选择，不让它在触摸模式下拿焦点：否则手指一按输出区就把输入法
            // 拽到自己身上，隐藏的输入视图再也收不到字符。
            isFocusableInTouchMode = false
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownX = event.x
                        touchDownY = event.y
                        touchDownAt = SystemClock.uptimeMillis()
                        false
                    }

                    MotionEvent.ACTION_UP -> {
                        val moved = kotlin.math.abs(event.x - touchDownX) + kotlin.math.abs(event.y - touchDownY)
                        val quick = SystemClock.uptimeMillis() - touchDownAt < 300
                        val tap = moved < dp(10f).toFloat() && quick
                        if (tap) toggleKeyboard()
                        // 轻点必须消费掉：这个输出区是可选中文本，按下时它会把焦点抢过去，
                        // 若把抬手继续交给它，输入法就会绑在输出区上，键盘输入再也进不了会话。
                        tap
                    }

                    else -> false
                }
            }
        }
        scroll = ScrollView(host).apply {
            isFillViewport = true
            setBackgroundColor(terminalBackground)
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
        status = label("正在启动会话", 12.5f, terminalMuted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = pill(0xFF141414.toInt())
        }
        val area = FrameLayout(host)
        area.setBackgroundColor(terminalBackground)
        area.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        area.addView(input, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        area.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return area
    }

    /** Termux 的默认键位：上一行功能键，下一行修饰键与方向键。 */
    private fun keyArea(): View {
        val bar = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(terminalBackground)
            setPadding(dp(4f), dp(2f), dp(4f), dp(4f))
        }
        bar.addView(keyRow(firstRow))
        bar.addView(keyRow(secondRow))
        return bar
    }

    private fun keyRow(keys: List<KeySpec>): View {
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        for (spec in keys) {
            row.addView(keyCell(spec), LinearLayout.LayoutParams(0, dp(34f), 1f))
        }
        return row
    }

    private fun keyCell(spec: KeySpec): TextView {
        val cell = TextView(host).apply {
            text = spec.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(terminalForeground)
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
            cell.setTextColor(if (armed) terminalAccent else terminalForeground)
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
        // 先把焦点拉回输入视图：功能键按下会移动触摸焦点，焦点一旦离开，
        // 输入法就会跟着走，键盘看起来像被莫名收起。
        refocusInput()
        var out = bytes
        if (ctrlArmed && out.length == 1) out = controlCode(out[0]).toString()
        if (altArmed) out = "\u001b$out"
        writeRaw(out)
        if (ctrlArmed || altArmed) setModifiers(false, false)
    }

    // ---------------------------------------------------------------- 输入

    /**
     * 点终端区弹出或收起软键盘，按键直接进会话，不再有独立输入框。
     *
     * 输入视图留在 1×1 的可见位置（不能是 GONE）：完全不可见的视图拿不到输入法焦点。
     */
    private fun toggleKeyboard() {
        val manager = host.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (keyboardShown) {
            manager.hideSoftInputFromWindow(input.windowToken, 0)
            keyboardShown = false
        } else {
            refocusInput()
            manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            keyboardShown = true
        }
        cursorOn = true
        render()
    }

    /**
     * 把焦点还给输入视图。点过输出区（它可以拖动选择文本）或键盘收起之后，
     * 焦点可能已经跑掉，下一次点终端区必须先把焦点拉回来，输入法才会重新挂上。
     */
    private fun refocusInput() {
        if (!input.hasFocus()) input.requestFocus()
    }

    /** 终端区的 IME 目标：把键盘输入按字节送进 shell。 */
    private inner class TerminalInput(context: Context) : View(context) {

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            // 不能声明 TYPE_NULL：那在多数输入法里等于"这里不接收文本"，键盘会切成无输入
            // 状态并且不再回调 commitText，于是敲什么都不会有反应。这里按普通多行文本声
            // 明，同时关掉联想，避免候选词与自动更正来抢输入。
            //
            // 也绝不能用任何 VARIATION_PASSWORD 变体（包括 VISIBLE_PASSWORD）：输入法看
            // 到 PASSWORD 就切到密码键盘——空格式键盘、禁用剪贴板、回车键变成"完成"，
            // 而终端需要的是完整的符号键盘与真正的换行。改用默认变体，隐私需求靠
            // imeOptions 的 NO_PERSONALIZED_LEARNING 满足。
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            outAttrs.initialCapsMode = 0
            return object : BaseInputConnection(this@TerminalInput, false) {

                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    if (!text.isNullOrEmpty()) typeText(text.toString())
                    return true
                }

                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    // 输入法的候选串只是中间态，这里不做处理，等它 commit 时统一送进会话。
                    return true
                }

                override fun finishComposingText(): Boolean = true

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength) { typeText(BACKSPACE) }
                    return true
                }

                override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength) { typeText(BACKSPACE) }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action != KeyEvent.ACTION_DOWN) return true
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> submitLine()
                        KeyEvent.KEYCODE_DEL -> typeText(BACKSPACE)
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
        cursorOn = true
        render()
    }

    private fun submitLine() {
        lines.append(PROMPT).append(echo).append('\n')
        echo.setLength(0)
        writeRaw("\n")
        cursorOn = true
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
            append(PROMPT).append(echo)
            append(if (cursorOn) CURSOR else ' ')
        }
        transcript.text = lines.toString() + tail
        if (follow) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun pill(fill: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(8f).toFloat()
            setColor(fill)
        }

    private class KeySpec(
        val label: String,
        val bytes: String,
        val popup: String? = null,
        val special: String? = null,
    )

    private val terminalBackground = 0xFF000000.toInt()
    private val terminalForeground = 0xFFE0E0E0.toInt()
    private val terminalMuted = 0xFF7A7A7A.toInt()
    private val terminalAccent = 0xFF8AB4F8.toInt()

    private companion object {
        const val PROMPT = "\u203a "
        const val CURSOR = "\u2588"
        const val BLINK_MS = 530L
        const val MAX_CHARS = 120_000

        /** 退格：终端里就是发送删除字符。 */
        const val BACKSPACE = "\u007f"

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
