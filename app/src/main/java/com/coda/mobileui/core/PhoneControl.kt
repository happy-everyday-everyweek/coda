package com.coda.mobileui.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/** 手机控制业务错误（转成 MCP 工具错误返回）。 */
class ToolException(message: String) : Exception(message)

/**
 * 手机控制管理器：开关、token、本地 MCP 服务器与内核配置同步。
 *
 * 方案：Coda 在 127.0.0.1 上提供 MCP（Streamable HTTP）服务器，暴露手机操作工具；
 * 内核以 type=http 的 MCP 服务器连接它（配置写入 HOME/.zcode/cli/config.json 的 mcp.servers）。
 * 安全模型借鉴 google/artemis 助手：仅回环地址 + Bearer token 鉴权，默认关闭。
 */
object PhoneControl {

    const val PORT = 39217
    private const val PREFS = "coda_phone_control"
    private const val SERVER_NAME = "coda-mobile"

    @Volatile
    private var server: PhoneControlServer? = null

    private val HEX = "0123456789abcdef".toCharArray()

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    /** 会话 token：首次生成后持久化；写进 MCP 配置的 Authorization 头。 */
    fun token(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = sp.getString("token", null)
        if (!existing.isNullOrEmpty()) return existing
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(64)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        val t = sb.toString()
        sp.edit().putString("token", t).apply()
        return t
    }

    fun isServerRunning(): Boolean = server?.isRunning == true

    fun statusText(ctx: Context): String = when {
        !isEnabled(ctx) -> "已停用"
        isServerRunning() -> "运行中 · 127.0.0.1:$PORT"
        else -> "已停止"
    }

    /** App 启动时调用：开关开启则确保服务器在跑。 */
    fun startIfEnabled(ctx: Context) {
        if (isEnabled(ctx)) startServer(ctx)
    }

    @Synchronized
    fun startServer(ctx: Context): String? {
        if (server?.isRunning == true) return null
        val s = PhoneControlServer(ctx.applicationContext, PORT, token(ctx))
        val err = s.start()
        if (err != null) return err
        server = s
        return null
    }

    @Synchronized
    fun stopServer() {
        server?.stop()
        server = null
    }

    /** 开关切换总入口：启停服务器 + 同步内核 MCP 配置。返回 null 表示成功。 */
    fun applyEnabled(ctx: Context, enable: Boolean): String? {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enable).apply()
        if (enable) {
            val err = startServer(ctx)
            if (err != null) return "服务启动失败：$err"
            syncMcpConfig(ctx, true)
            return null
        }
        stopServer()
        syncMcpConfig(ctx, false)
        return null
    }

    /** 去掉 MCP 配置里的手机控制条目（卸载/停用时保持内核配置干净）。 */
    fun syncMcpConfig(ctx: Context, enable: Boolean): Boolean {
        return try {
            val f = configFile(ctx)
            val root = if (f.exists()) {
                try {
                    JSONObject(f.readText())
                } catch (_: Throwable) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }
            val mcp = root.optJSONObject("mcp") ?: JSONObject().also { root.put("mcp", it) }
            val servers = mcp.optJSONObject("servers") ?: JSONObject().also { mcp.put("servers", it) }
            if (enable) {
                servers.put(
                    SERVER_NAME,
                    JSONObject()
                        .put("type", "http")
                        .put("url", "http://127.0.0.1:$PORT/mcp")
                        .put("enabled", true)
                        .put(
                            "headers",
                            JSONObject().put("Authorization", "Bearer " + token(ctx)),
                        ),
                )
            } else {
                servers.remove(SERVER_NAME)
            }
            f.parentFile?.mkdirs()
            f.writeText(root.toString(2))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun configFile(ctx: Context): File =
        File(File(ctx.filesDir, "home"), ".zcode/cli/config.json")

    fun openAccessibilitySettings(ctx: Context) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}

/**
 * 本地 MCP 服务器（MCP Streamable HTTP 的 JSON 响应模式）。
 * 单请求短连接：应答后关闭连接（Connection: close），客户端自动重连。
 */
class PhoneControlServer(
    private val ctx: Context,
    private val port: Int,
    private val token: String,
) {

    @Volatile
    var isRunning = false
        private set

    private var serverSocket: ServerSocket? = null

    fun start(): String? {
        return try {
            val ss = ServerSocket()
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
            serverSocket = ss
            isRunning = true
            Thread({ acceptLoop(ss) }, "coda-phone-control").start()
            null
        } catch (e: Throwable) {
            "端口 $port 不可用：${e.message}"
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning) {
            val socket = try {
                ss.accept()
            } catch (_: Throwable) {
                break
            }
            Thread({ handleConnection(socket) }, "coda-phone-conn").start()
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            var auth: String? = null
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "authorization" -> auth = value
                }
            }
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                writeEmpty(output, 413)
                return
            }
            val body = if (contentLength > 0) readExactly(input, contentLength) else ByteArray(0)
            route(output, method, path, auth, body)
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun route(output: OutputStream, method: String, path: String, auth: String?, body: ByteArray) {
        when {
            method == "POST" && path == "/mcp" -> {
                if (auth != "Bearer $token") {
                    writeEmpty(output, 401)
                    return
                }
                val text = String(body, Charsets.UTF_8)
                val response = try {
                    PhoneMcp.handle(ctx, text)
                } catch (e: Throwable) {
                    PhoneMcp.protocolError(text, e)
                }
                if (response == null) {
                    writeEmpty(output, 202)
                } else {
                    writeJson(output, 200, response)
                }
            }
            method == "GET" && path == "/mcp" -> writeEmpty(output, 405)
            method == "GET" && path == "/ping" ->
                writeJson(output, 200, """{"ok":true,"name":"coda-mobile"}""")
            else -> writeEmpty(output, 404)
        }
    }

    private fun writeJson(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun writeEmpty(output: OutputStream, code: Int) {
        val head = "HTTP/1.1 $code\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(256)
        var b = input.read()
        if (b < 0) return null
        while (b >= 0) {
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_HEADER_BYTES) throw IllegalStateException("header too long")
            b = input.read()
        }
        return String(buf.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var total = 0
        while (total < length) {
            val r = input.read(buf, total, length - total)
            if (r < 0) break
            total += r
        }
        return if (total == length) buf else buf.copyOf(total)
    }

    companion object {
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}

/**
 * MCP 协议处理：initialize / tools/list / tools/call / ping；
 * 通知类消息（无 id）返回 202。工具执行全部经由无障碍服务。
 */
object PhoneMcp {

    private val seq = AtomicInteger(1)

    fun handle(ctx: Context, body: String): String? {
        val req = try {
            JSONObject(body)
        } catch (_: Throwable) {
            return """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"parse error"}}"""
        }
        val method = req.optString("method")
        val id = if (req.has("id") && !req.isNull("id")) req.get("id") else null
        if (id == null) {
            // 通知（initialized / cancelled 等）：接受并返回空 202。
            return null
        }
        return try {
            val result: Any = when (method) {
                "initialize" -> initializeResult(req)
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", toolsList())
                "tools/call" -> callTool(ctx, req.optJSONObject("params") ?: JSONObject())
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> return errorJson(id, -32601, "Method not found: $method")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
        } catch (e: Throwable) {
            errorJson(id, -32603, e.toString())
        }
    }

    /** 服务器无法判断请求 id 时的兜底错误（解析失败等）。 */
    fun protocolError(body: String, e: Throwable): String {
        val id = try {
            val o = JSONObject(body)
            if (o.has("id") && !o.isNull("id")) o.get("id") else null
        } catch (_: Throwable) {
            null
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", -32603).put("message", e.toString()))
            .toString()
    }

    private fun initializeResult(req: JSONObject): JSONObject {
        val pv = req.optJSONObject("params")?.optString("protocolVersion")
            ?.takeIf { it.isNotEmpty() } ?: "2025-06-18"
        return JSONObject()
            .put("protocolVersion", pv)
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject().put("name", "coda-mobile").put("version", "1.0.0"))
    }

    private fun errorJson(id: Any, code: Int, message: String): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    // ------------------------------------------------------------ 工具定义

    private fun strProp(desc: String): JSONObject =
        JSONObject().put("type", "string").put("description", desc)

    private fun numProp(desc: String): JSONObject =
        JSONObject().put("type", "number").put("description", desc)

    private fun boolProp(desc: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", desc)

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", props)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)
    }

    private fun toolsList(): JSONArray {
        val arr = JSONArray()
        arr.put(
            tool(
                "dump_ui",
                "读取当前屏幕的界面树（包名、控件文本/ID/类型/坐标/可点击等）。操作手机前先用它确认界面。",
                JSONObject().put("include_invisible", boolProp("是否包含不可见节点，默认 false")),
                emptyList(),
            ),
        )
        arr.put(
            tool(
                "screenshot",
                "截取当前屏幕。默认保存为 PNG 文件并返回路径；inline=true 时同时在结果里内嵌图片。",
                JSONObject()
                    .put("inline", boolProp("是否内嵌图片数据（消耗较多上下文），默认 false")),
                emptyList(),
            ),
        )
        arr.put(
            tool(
                "tap",
                "点击屏幕坐标。坐标可用 dump_ui 的 bounds 计算（取中心点）。",
                JSONObject().put("x", numProp("横坐标")).put("y", numProp("纵坐标")),
                listOf("x", "y"),
            ),
        )
        arr.put(
            tool(
                "long_press",
                "长按屏幕坐标。",
                JSONObject().put("x", numProp("横坐标")).put("y", numProp("纵坐标"))
                    .put("duration_ms", numProp("按住时长毫秒，默认 600")),
                listOf("x", "y"),
            ),
        )
        arr.put(
            tool(
                "swipe",
                "从一点滑动到另一点（滚动列表/翻页）。",
                JSONObject().put("x1", numProp("起点横坐标")).put("y1", numProp("起点纵坐标"))
                    .put("x2", numProp("终点横坐标")).put("y2", numProp("终点纵坐标"))
                    .put("duration_ms", numProp("滑动时长毫秒，默认 300")),
                listOf("x1", "y1", "x2", "y2"),
            ),
        )
        arr.put(
            tool(
                "tap_element",
                "按文本/描述/资源 ID 查找控件并点击其中心（自动寻找可点击祖先）。比坐标点击更稳。",
                JSONObject().put("text", strProp("控件文本（包含匹配）"))
                    .put("desc", strProp("contentDescription（包含匹配）"))
                    .put("id", strProp("资源 ID 短名，如 btn_ok")),
                emptyList(),
            ),
        )
        arr.put(
            tool(
                "type_text",
                "向当前焦点输入框写入文本（会替换原有内容）。",
                JSONObject().put("text", strProp("要输入的文本")),
                listOf("text"),
            ),
        )
        arr.put(
            tool(
                "press_key",
                "执行系统按键：back / home / recents / notifications。",
                JSONObject().put("key", strProp("按键名")),
                listOf("key"),
            ),
        )
        arr.put(
            tool(
                "open_app",
                "启动一个已安装的应用（按名称模糊匹配或包名）。",
                JSONObject().put("name", strProp("应用名（模糊匹配）"))
                    .put("package", strProp("包名（精确）")),
                emptyList(),
            ),
        )
        arr.put(
            tool(
                "wait",
                "等待一段时间（页面加载等），最长 30000 毫秒。",
                JSONObject().put("ms", numProp("等待毫秒数")),
                listOf("ms"),
            ),
        )
        return arr
    }

    // ------------------------------------------------------------ 工具执行

    private class ToolOutput(val text: String, val imageBase64: String? = null)

    private fun callTool(ctx: Context, params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            val out = executeTool(ctx, name, args)
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", out.text))
            out.imageBase64?.let {
                content.put(JSONObject().put("type", "image").put("data", it).put("mimeType", "image/png"))
            }
            JSONObject().put("content", content).put("isError", false)
        } catch (e: ToolException) {
            errorResult(e.message ?: "工具执行失败")
        } catch (e: Throwable) {
            errorResult(e.toString())
        }
    }

    private fun errorResult(message: String): JSONObject {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", message))
        return JSONObject().put("content", content).put("isError", true)
    }

    private fun service(): PhoneAccessibilityService =
        PhoneAccessibilityService.get()
            ?: throw ToolException("无障碍服务未启用：请在 Coda → 设置 → 手机控制 中开启无障碍授权")

    private fun executeTool(ctx: Context, name: String, args: JSONObject): ToolOutput {
        return when (name) {
            "dump_ui" -> {
                val json = service().dumpUi(args.optBoolean("include_invisible", false))
                ToolOutput(json.toString())
            }
            "screenshot" -> screenshot(ctx, args.optBoolean("inline", false))
            "tap" -> {
                val ok = service().tap(args.optDouble("x", 0.0).toFloat(), args.optDouble("y", 0.0).toFloat())
                ToolOutput(if (ok) "已点击 (${args.optInt("x")}, ${args.optInt("y")})" else "点击失败")
            }
            "long_press" -> {
                val ok = service().longPress(
                    args.optDouble("x", 0.0).toFloat(),
                    args.optDouble("y", 0.0).toFloat(),
                    args.optLong("duration_ms", 600L).coerceIn(100L, 10_000L),
                )
                ToolOutput(if (ok) "已长按 (${args.optInt("x")}, ${args.optInt("y")})" else "长按失败")
            }
            "swipe" -> {
                val ok = service().swipe(
                    args.optDouble("x1", 0.0).toFloat(),
                    args.optDouble("y1", 0.0).toFloat(),
                    args.optDouble("x2", 0.0).toFloat(),
                    args.optDouble("y2", 0.0).toFloat(),
                    args.optLong("duration_ms", 300L),
                )
                ToolOutput(if (ok) "已滑动" else "滑动失败")
            }
            "tap_element" -> tapElement(args)
            "type_text" -> {
                val text = args.optString("text")
                if (text.isEmpty()) throw ToolException("text 不能为空")
                val ok = service().setText(text)
                ToolOutput(if (ok) "已输入文本（${text.length} 字）" else "输入失败：未找到可编辑的输入框")
            }
            "press_key" -> {
                val key = args.optString("key")
                val ok = service().globalAction(key)
                ToolOutput(if (ok) "已执行 $key" else "不支持的按键: $key（可用 back/home/recents/notifications）")
            }
            "open_app" -> openApp(ctx, args)
            "wait" -> {
                val ms = args.optLong("ms", 1000L).coerceIn(0L, 30_000L)
                Thread.sleep(ms)
                ToolOutput("已等待 ${ms}ms")
            }
            else -> throw ToolException("未知工具: $name")
        }
    }

    private fun screenshot(ctx: Context, inline: Boolean): ToolOutput {
        val bmp = service().screenshotBitmap()
            ?: throw ToolException("截图失败（需要 Android 11+，且无障碍服务已授权）")
        val dir = File("/sdcard/Coda/workspace/.coda/screenshots")
        dir.mkdirs()
        val f = File(dir, "screen-${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Throwable) {
            throw ToolException("截图保存失败: ${e.message}")
        }
        val info = "saved: ${f.absolutePath} (${bmp.width}x${bmp.height})"
        if (!inline) return ToolOutput(info)
        return try {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            ToolOutput(info, Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
        } catch (e: Throwable) {
            ToolOutput(info)
        }
    }

    private fun tapElement(args: JSONObject): ToolOutput {
        val text = args.optString("text").takeIf { it.isNotEmpty() }
        val desc = args.optString("desc").takeIf { it.isNotEmpty() }
        val rid = args.optString("id").takeIf { it.isNotEmpty() }
        if (text == null && desc == null && rid == null) {
            throw ToolException("需提供 text / desc / id 之一")
        }
        val svc = service()
        val root = svc.rootInActiveWindow ?: throw ToolException("当前没有活动窗口")
        val node = findNode(root, text, desc, rid)
            ?: throw ToolException("未找到匹配的控件（可先调用 dump_ui 查看界面）")
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        var target: android.view.accessibility.AccessibilityNodeInfo? = node
        var clickable: android.view.accessibility.AccessibilityNodeInfo? = null
        while (target != null) {
            if (target.isClickable) {
                clickable = target
                break
            }
            target = target.parent
        }
        val cx: Float
        val cy: Float
        if (clickable != null) {
            val r = Rect()
            clickable.getBoundsInScreen(r)
            cx = r.exactCenterX()
            cy = r.exactCenterY()
        } else {
            cx = nodeRect.exactCenterX()
            cy = nodeRect.exactCenterY()
        }
        if (cx <= 0f || cy <= 0f) throw ToolException("控件坐标为 0，可能不可见")
        val ok = svc.tap(cx, cy)
        return ToolOutput(
            if (ok) {
                "已点击「${text ?: desc ?: rid}」(${cx.toInt()}, ${cy.toInt()})"
            } else {
                "点击失败"
            },
        )
    }

    private fun findNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        text: String?,
        desc: String?,
        rid: String?,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.isVisibleToUser) {
            val t = node.text?.toString()
            if (text != null && t != null && t.contains(text, ignoreCase = true)) return node
            val d = node.contentDescription?.toString()
            if (desc != null && d != null && d.contains(desc, ignoreCase = true)) return node
            val id = node.viewIdResourceName?.substringAfterLast('/')
            if (rid != null && id != null && id.contains(rid, ignoreCase = true)) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, text, desc, rid)?.let { return it }
        }
        return null
    }

    private fun openApp(ctx: Context, args: JSONObject): ToolOutput {
        var target = args.optString("package").takeIf { it.isNotEmpty() }
        val query = args.optString("name").takeIf { it.isNotEmpty() }
        if (target == null && query == null) throw ToolException("需提供 name 或 package")
        val pm = ctx.packageManager
        if (target == null && query != null) {
            val apps = try {
                pm.getInstalledApplications(0)
            } catch (_: Throwable) {
                emptyList()
            }
            val hit = apps.firstOrNull { a ->
                val label = try {
                    pm.getApplicationLabel(a).toString()
                } catch (_: Throwable) {
                    ""
                }
                label.contains(query, ignoreCase = true) || a.packageName.contains(query, ignoreCase = true)
            } ?: throw ToolException("未找到应用：$query")
            target = hit.packageName
        }
        val pkg = target ?: throw ToolException("需提供 name 或 package")
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: throw ToolException("应用不可启动：$pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            ToolOutput("已启动 $pkg")
        } catch (e: Throwable) {
            throw ToolException("启动失败（系统可能限制后台启动）：${e.message}")
        }
    }
}