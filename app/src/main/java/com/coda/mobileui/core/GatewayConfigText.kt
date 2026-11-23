package com.coda.mobileui.core

import org.json.JSONObject

/**
 * 网关配置的文本处理：日志脱敏与 bootstrap 临时端口。
 *
 * 从 WorkBuddyGateway 抽出来的纯函数层，不碰 Context / 进程 / 网络，
 * 这样「日志有没有漏凭证」「bootstrap 会不会抢真实端口」可以单测断言，而不是靠真机复现。
 */
object GatewayConfigText {
    /**
     * 需要脱敏的字段名判据。
     *
     * 用「整段名匹配」而不是 contains("key")：monkey、keyboard_layout 这类字段名里也有 key，
     * 按子串打码会把正常配置也糊掉，反而降低日志的排障价值。
     */
    private fun isSecretName(name: String): Boolean {
        // 归一化（OCR #820）：camelCase 先拆词再 lowercase（顺序反了分不出
        // 词界），连字符转下划线；secret-key / api-keys / apiKey / accessToken
        // 都要能命中。复数只剥一个尾部 s。
        val f =
            name
                .trim('"', '\'', ' ')
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .lowercase()
                .replace('-', '_')
        val bare = f.removeSuffix("s")
        return listOf(f, bare).any { n ->
            n == "token" || n == "secret" || n == "password" || n == "authorization" ||
                n.endsWith("_key") || n.endsWith("_token") || n.endsWith("_secret") || n.endsWith("_password")
        }
    }

    /**
     * JSON 形式：只把命中字段的值替换掉，其余字节原样保留。
     *
     * 刻意不走 JSONObject.toString()：它会重排键顺序、改写空白，
     * 让「没有凭证的配置」在日志里也变成另一副样子，反而不利于比对。
     */
    private fun redactJson(text: String): String? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        // 嵌套对象/数组里的凭证同样要脱敏（OCR #820）：配置形状由上游二进制
        // 生成，secret 完全可能在二层三层。递归收集全部命中键名。
        val secrets = LinkedHashSet<String>()
        fun collect(obj: JSONObject) {
            for (k in obj.keys()) {
                if (isSecretName(k)) secrets.add(k)
                when (val v = obj.opt(k)) {
                    is JSONObject -> collect(v)
                    is org.json.JSONArray ->
                        for (i in 0 until v.length()) {
                            (v.opt(i) as? JSONObject)?.let { collect(it) }
                        }
                }
            }
        }
        collect(root)
        if (secrets.isEmpty()) return text
        var out = text
        for (key in secrets) {
            // 只替换 "key" 后面紧跟的那一个值，避免把文中同名字符串一起打码。
            val pattern = Regex("(\"" + Regex.escape(key) + "\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\s]+)")
            out = pattern.replace(out) { m -> m.groupValues[1] + "\"***\"" }
        }
        return out
    }

    /**
     * 逐行形式：处理 `api_key = "..."` 这类非 JSON 写法。
     *
     * 只替换分隔符右侧的值，字段名与缩进保留，日志仍能看出配置结构。
     */
    private fun redactLines(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOfFirst { it == '=' || it == ':' }
            if (idx <= 0) return@joinToString line
            val name = line.substring(0, idx)
            if (!isSecretName(name)) return@joinToString line
            val indent = name.takeWhile { it == ' ' || it == '\t' }
            val bare = name.trim()
            "$indent$bare${line[idx]} ***"
        }

    /** 把配置里的凭证字段替换成 ***，用于写日志。非 JSON 也能处理。 */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{")) {
            redactJson(text)?.let { return it }
        }
        return redactLines(text)
    }

    /** 先脱敏再截断：顺序反了会把半个凭证留在日志里。 */
    fun preview(
        text: String,
        limit: Int,
    ): String = redact(text).take(limit)

    /**
     * bootstrap 用的临时端口：必须与真实网关端口不同（#428）。
     *
     * 选一个远离默认段的高位端口，避免与 DEFAULT_PORT 及其后两个候选（+1/+2）相撞。
     */
    fun bootstrapPort(gatewayPort: Int): Int {
        val candidate = 45999
        if (candidate != gatewayPort) return candidate
        // 真实端口恰好等于候选值时退到相邻端口，仍然保证不相等且在合法范围内。
        return if (candidate < 65535) candidate + 1 else candidate - 1
    }

    /**
     * 把 listen 写进 bootstrap 配置。
     *
     * 只传 `-config <tmp>` 不足以指定端口：程序会用自带默认端口，可能撞上真实网关。
     * 解析不了就原样返回，让调用方按「未能生成配置」处理，而不是写坏文件。
     */
    fun seedListen(
        text: String,
        port: Int,
    ): String {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return text
        return runCatching {
            root.put("listen", "127.0.0.1:$port")
            root.toString(2)
        }.getOrDefault(text)
    }
}