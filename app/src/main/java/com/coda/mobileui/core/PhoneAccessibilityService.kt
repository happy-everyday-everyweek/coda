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
        fun isEnabled(): Boolean {
            return instanceRef != null
        }

        /** 从系统设置读取启用状态（可跨进程重启场景）。 */
        fun isEnabledInSystem(context: android.content.Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ) ?: return false
                val full = context.packageName + "/" + PhoneAccessibilityService::class.java.name
                val short = context.packageName + "/." + PhoneAccessibilityService::class.java.name
                    .removePrefix(context.packageName + ".")
                enabled.contains(full) || enabled.contains(short)
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
    fun dumpUi(includeInvisible: Boolean = false, maxNodes: Int = 900, maxDepth: Int = 50): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("error", "当前没有活动窗口")
        val out = JSONObject()
        out.put("package", root.packageName?.toString() ?: "")
        val counter = intArrayOf(0)
        val nodes = JSONArray()
        try {
            appendChildren(root, nodes, 0, maxDepth, counter, maxNodes, includeInvisible)
        } catch (_: Throwable) {
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
        maxNodes: Int,
        includeInvisible: Boolean,
    ) {
        if (depth > maxDepth || counter[0] >= maxNodes) return
        for (i in 0 until node.childCount) {
            if (counter[0] >= maxNodes) return
            val child = node.getChild(i) ?: continue
            val obj = nodeToJson(child, includeInvisible)
            if (obj != null) {
                counter[0]++
                val kids = JSONArray()
                appendChildren(child, kids, depth + 1, maxDepth, counter, maxNodes, includeInvisible)
                if (kids.length() > 0) obj.put("children", kids)
                into.put(obj)
            }
        }
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, includeInvisible: Boolean): JSONObject? {
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
        if (obj.length() <= 1) return null // 只有 bounds 的空壳节点，跳过
        return obj
    }

    // ------------------------------------------------------------ 手势

    /** 单击指定坐标。 */
    fun tap(x: Float, y: Float): Boolean = gestureTap(x, y, 60L)

    /** 长按指定坐标。 */
    fun longPress(x: Float, y: Float, durationMs: Long = 600L): Boolean = gestureTap(x, y, durationMs)

    private fun gestureTap(x: Float, y: Float, durationMs: Long): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
            false
        }
    }

    /** 滑动。 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 5000L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------ 文本与按键

    /** 向当前焦点输入框写入文本；无焦点时尝试第一个可编辑节点。 */
    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findFocusedEditable(root) ?: findFirstEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Throwable) {
            false
        }
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        var n: AccessibilityNodeInfo? = focused
        while (n != null) {
            if (n.isEditable) return n
            n = n.parent
        }
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && root.isVisibleToUser) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findFirstEditable(child)?.let { return it }
        }
        return null
    }

    /** 全局动作：back / home / recents / notifications。 */
    fun globalAction(name: String): Boolean {
        val action = when (name.lowercase()) {
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
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val buffer = screenshot.hardwareBuffer
                    result = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                } catch (_: Throwable) {
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        }
        return try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            result
        } catch (_: Throwable) {
            null
        }
    }
}