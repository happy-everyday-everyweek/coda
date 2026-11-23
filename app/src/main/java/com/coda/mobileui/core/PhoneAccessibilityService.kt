package com.coda.mobileui.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 手机控制执行端：无障碍服务。
 *
 * 借鉴 google/artemis 无障碍助手（Apache-2.0）的设计：读取当前窗口的节点树、
 * 注入手势（点击/长按/滑动）、设置文本、执行全局动作与截图。
 * 所有能力仅供本机 App 内的 MCP 服务器调用（loopback + token），不导出任何对外接口。
 */
class PhoneAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        private var instanceRef: PhoneAccessibilityService? = null

        /** 当前已连接的服务实例；未启用时为 null。 */
        fun get(): PhoneAccessibilityService? = instanceRef

        /** 检查无障碍服务是否已在系统设置中启用（用于界面展示）。 */
        fun isEnabled(): Boolean = instanceRef != null

        /** 从系统设置读取启用状态（可跨进程重启场景）。 */
        fun isEnabledInSystem(context: android.content.Context): Boolean {
            return try {
                val enabled =
                    Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ) ?: return false
                // 冒号分隔的 pkg/cls 列表按条目精确比较：子串 contains 会把
                // 名字里恰好包含目标串的其他服务误判为已启用。
                val cls = PhoneAccessibilityService::class.java.name
                val full = context.packageName + "/" + cls
                // short 形式只在类确实位于应用包下时构造，否则不生成伪条目。
                val short =
                    if (cls.startsWith(context.packageName + ".")) {
                        context.packageName + "/." + cls.removePrefix(context.packageName + ".")
                    } else {
                        null
                    }
                enabled.split(':').any { it == full || (short != null && it == short) }
            } catch (_: Throwable) {
                false
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { r -> mainHandler.post(r) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无需处理事件；读屏按需拉取。
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (instanceRef === this) instanceRef = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 读屏

    /**
     * 导出当前活动窗口的节点树。字段取模型可用性优先的短名；
     * 带深度与节点数上限，避免超大页面生成 MB 级 JSON。
     */
    fun dumpUi(
        includeInvisible: Boolean = false,
        maxNodes: Int = 900,
        maxDepth: Int = 50,
    ): JSONObject {
        val root =
            rootInActiveWindow
                ?: return JSONObject().put("error", "当前没有活动窗口")
        val out = JSONObject()
        out.put("package", root.packageName?.toString() ?: "")
        val counter = intArrayOf(0)
        // OCR #819：被过滤的容器也会整棵递归，必须另设访问预算，
        // 否则 includeInvisible=false 时 maxNodes 约束不住遍历规模。
        val visited = intArrayOf(0)
        val nodes = JSONArray()
        try {
            appendChildren(root, nodes, 0, maxDepth, counter, visited, maxNodes, includeInvisible)
        } catch (e: Exception) {
            // 异常降级必须可见：部分结果与完整结果不能长得一样。
            AppLog.w("PhoneAccessibility", "dumpUi 遍历中断，返回部分结果：${e.message}")
            out.put("partial", true)
            out.put("error", e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { root.recycle() }
        }
        out.put("nodeCount", counter[0])
        out.put("truncated", counter[0] >= maxNodes)
        out.put("nodes", nodes)
        return out
    }

    private fun appendChildren(
        node: AccessibilityNodeInfo,
        into: JSONArray,
        depth: Int,
        maxDepth: Int,
        counter: IntArray,
        visited: IntArray,
        maxNodes: Int,
        includeInvisible: Boolean,
    ) {
        if (depth > maxDepth || counter[0] >= maxNodes || visited[0] >= maxNodes) return
        for (i in 0 until node.childCount) {
            if (counter[0] >= maxNodes || visited[0] >= maxNodes) return
            val child = node.getChild(i) ?: continue
            visited[0]++
            try {
                val obj = nodeToJson(child, includeInvisible)
                if (obj != null) counter[0]++
                // 即使当前节点被过滤，也必须继续遍历它的后代：布局容器本身通常没有文本。
                val kids = JSONArray()
                appendChildren(child, kids, depth + 1, maxDepth, counter, visited, maxNodes, includeInvisible)
                if (obj != null) {
                    if (kids.length() > 0) obj.put("children", kids)
                    into.put(obj)
                } else if (kids.length() > 0) {
                    // 被过滤的容器：把子节点提升到当前层，避免整棵子树消失。
                    for (k in 0 until kids.length()) into.put(kids.get(k))
                }
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    private fun nodeToJson(
        node: AccessibilityNodeInfo,
        includeInvisible: Boolean,
    ): JSONObject? {
        if (!includeInvisible && !node.isVisibleToUser) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!includeInvisible && (rect.width() <= 0 || rect.height() <= 0)) return null
        val obj = JSONObject()
        val text = node.text?.toString()?.take(200)
        if (!text.isNullOrEmpty()) obj.put("text", text)
        val desc = node.contentDescription?.toString()?.take(160)
        if (!desc.isNullOrEmpty()) obj.put("desc", desc)
        val rid = node.viewIdResourceName?.substringAfterLast('/')
        if (!rid.isNullOrEmpty()) obj.put("id", rid)
        val cls = node.className?.toString()?.substringAfterLast('.')
        if (!cls.isNullOrEmpty()) obj.put("cls", cls)
        obj.put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
        if (node.isClickable) obj.put("clickable", true)
        if (node.isScrollable) obj.put("scrollable", true)
        if (node.isEditable) obj.put("editable", true)
        if (node.isFocused) obj.put("focused", true)
        if (node.isCheckable) obj.put("checkable", true).also { if (node.isChecked) obj.put("checked", true) }
        if (node.isSelected) obj.put("selected", true)
        if (!node.isEnabled) obj.put("disabled", true)
        // 只有 bounds 的容器也要保留：它是子控件的挂载点，删掉会连带丢掉整棵子树。
        return obj
    }

    // ------------------------------------------------------------ 手势

    /** 单击指定坐标。 */
    fun tap(
        x: Float,
        y: Float,
    ): Boolean = gestureTap(x, y, 60L)

    /** 长按指定坐标。 */
    fun longPress(
        x: Float,
        y: Float,
        durationMs: Long = 600L,
    ): Boolean = gestureTap(x, y, durationMs)

    private fun gestureTap(
        x: Float,
        y: Float,
        durationMs: Long,
    ): Boolean =
        try {
            val path = Path().apply { moveTo(x, y) }
            // 与 swipe 同一套范围约束：0/负数/超大值会让 StrokeDescription 抛
            // IllegalArgumentException，被吞掉后与「手势被拒」无法区分。
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(1L, 5000L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            AppLog.w("PhoneAccessibility", "tap 手势派发失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }

    /** 滑动。 */
    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L,
    ): Boolean =
        try {
            val path =
                Path().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 5000L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
            false
        }

    // ------------------------------------------------------------ 文本与按键

    /** 向当前焦点输入框写入文本；无焦点时尝试第一个可编辑节点。 */
    fun setText(text: String): Boolean {
        // OCR #819：root/target/查找途中拿到的节点都要回收，
        // 与 dumpUi/appendChildren 的回收纪律保持一致（API 26–32 上有实际意义）。
        var root: AccessibilityNodeInfo? = null
        var target: AccessibilityNodeInfo? = null
        return try {
            root = rootInActiveWindow ?: return false
            target = findFocusedEditable(root) ?: findFirstEditable(root) ?: return false
            val args =
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Throwable) {
            // 包括 StackOverflowError 在内的任何失败都按 false 返回，不能让进程崩溃。
            false
        } finally {
            runCatching { target?.recycle() }
            runCatching { root?.recycle() }
        }
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        var n: AccessibilityNodeInfo? = focused
        while (n != null) {
            if (n.isEditable) return n
            // 沿 parent 上行拿到的每个节点都是新实例，走过的要回收（OCR #819）。
            val parent = n.parent
            runCatching { n.recycle() }
            n = parent
        }
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 迭代 + 预算：无障碍树异常深或成环时，递归会 StackOverflow 直接把进程打崩。
        // OCR #819：getChild 拿到的实例必须回收——命中时清掉栈里剩余节点，
        // 未命中/预算耗尽时也要把整栈清空；root 归调用方持有，不在这里回收。
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && visited < 900) {
            val (node, depth) = stack.removeLast()
            visited++
            if (node.isEditable && node.isVisibleToUser) {
                found = node
                break
            }
            val canDescend = depth < 50
            if (canDescend) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    stack.addLast(child to depth + 1)
                }
            }
            if (node !== root) runCatching { node.recycle() }
        }
        while (stack.isNotEmpty()) {
            val (node, _) = stack.removeLast()
            if (node !== root) runCatching { node.recycle() }
        }
        return found
    }

    /** 全局动作：back / home / recents / notifications。 */
    fun globalAction(name: String): Boolean {
        val action =
            when (name.lowercase()) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return false
            }
        return try {
            performGlobalAction(action)
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------ 截图

    /** 截图（Android 11+；低版本返回 null）。 */
    fun screenshotBitmap(timeoutMs: Long = 8000L): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        // 回调由主线程执行，主线程同步等待会自锁到超时并可能 ANR：直接拒绝，交由后台线程调用。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            AppLog.w("PhoneAccessibility", "截图必须在后台线程调用")
            return null
        }
        val latch = CountDownLatch(1)
        // OCR #819：结果经原子引用建立 happens-before；超时后迟到的截图
        // 没人接手，必须在回调里就地回收，不能泄漏 bitmap。
        val result = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback =
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        val bmp =
                            Bitmap
                                .wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        if (bmp != null) {
                            if (abandoned.get()) {
                                runCatching { bmp.recycle() }
                            } else {
                                result.set(bmp)
                                // set 与超时侧 getAndSet 之间存在窗口：写入后复查旗标，
                                // 双方都用 getAndSet 认领，保证恰好一方回收。
                                if (abandoned.get()) {
                                    result.getAndSet(null)?.let { runCatching { it.recycle() } }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        // 系统级图形缓冲无论成功失败都要释放。
                        runCatching { buffer.close() }
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        return try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            val done =
                try {
                    latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            if (!done) {
                // 竞态兜底：置旗标后若结果恰好已写入，取走并回收，避免两头都没人管。
                abandoned.set(true)
                result.getAndSet(null)?.let { runCatching { it.recycle() } }
                return null
            }
            result.get()
        } catch (_: Throwable) {
            null
        }
    }
}
