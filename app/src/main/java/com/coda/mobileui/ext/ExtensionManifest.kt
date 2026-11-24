package com.coda.mobileui.ext

import com.coda.mobileui.core.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 拓展清单（`manifest.json`）。
 *
 * 拓展是应用层的可插拔能力包：一个目录加一份清单，按分类接入宿主。
 * 清单只描述"是什么、属于哪个分类、随包带了哪些二进制"，分类专属的能力声明放在同名段里
 * （例如终端拓展放在 `terminal` 段），因此以后新增分类不需要改动这份模型。
 */
data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    /** 分类标识；只有引擎里已注册的分类才允许装载。 */
    val category: String,
    val description: String,
    /** 随包分发的文件，装载时按权限释放到运行目录。 */
    val binaries: List<Binary>,
) {
    /**
     * 原始清单，供各分类读取自己的专属段。
     *
     * 刻意放在类体而不是主构造里（#471）：JSONObject 可变、非线程安全，且 equals/hashCode
     * 按引用比较——纳入 data class 状态会让内容相同的两份清单永不相等，toString 还会
     * 把整份 JSON 打进日志。现在相等性只由 id/name/version/category/description/binaries 决定。
     *
     * 读取时通过 [section] / [stringList] 取只读视图；需要直接改 raw 的调用方应自行复制。
     */
    var raw: JSONObject = JSONObject()
        internal set

    /** 相等性只看声明字段，不含 raw；toString 也不打印整份清单。 */
    override fun toString(): String = "ExtensionManifest(id=$id, name=$name, version=$version, category=$category)"

    /**
     * 一个随拓展分发的文件。
     *
     * 路径是相对拓展根目录的路径；同一个文件可以按 ABI 分层放在 `bin/<abi>/` 下，
     * 引擎解析时优先取当前设备 ABI 的那份，取不到再退回通用路径。
     *
     * [links] 是释放后一并创建的别名路径（相对运行目录）。多调用二进制（busybox、toybox 之类）
     * 靠 argv[0] 决定自己是哪个程序，必须让同一个文件以多个名字出现才算真正可用。
     */
    data class Binary(
        val path: String,
        val mode: Int,
        val links: List<String> = emptyList(),
    )

    /** 读取分类专属段；没有该段时返回空对象。 */
    fun section(key: String): JSONObject = raw.optJSONObject(key) ?: JSONObject()

    /** 读取清单一层的字符串数组。 */
    fun stringList(key: String): List<String> = stringsOf(raw.optJSONArray(key))

    companion object {
        /** 释放权限：0755（Kotlin 不支持八进制字面量，写成十进制 493）。 */
        const val MODE_EXECUTABLE = 493

        /** id 用作目录名与存储键，限制在安全字符集内。 */
        fun isValidId(id: String): Boolean =
            id.isNotEmpty() && id.length <= 96 &&
                // "." 与 ".." 会被当成当前目录/上级目录，释放路径会跑到扩展目录之外；
                // 以点开头的隐藏名也一并排除，避免和临时文件混在一起。
                !id.startsWith(".") &&
                id.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

        /** 解析清单；id 或分类缺失即视为非法清单。 */
        fun parse(text: String): ExtensionManifest? {
            val obj =
                try {
                    JSONObject(text)
                } catch (_: org.json.JSONException) {
                    return null
                } catch (e: StackOverflowError) {
                    // OCR #819：org.json 递归解析，恶意深嵌套清单会抛 StackOverflowError。
                    // collect() 对 parse 没有逐包保护，放它逃出去会触发 ensureLoaded 的
                    // catch-all 清空全部扩展（含内置）。按「这份清单无效」处理并留痕；
                    // OOM 等其它 Error 仍不吞（#472）。
                    AppLog.w("ExtensionManifest", "清单嵌套过深，按无效处理：${e.javaClass.simpleName}")
                    return null
                }
            val id = obj.optString("id", "").trim()
            val category = obj.optString("category", "").trim()
            if (!isValidId(id) || category.isEmpty()) return null
            return ExtensionManifest(
                id = id,
                name = obj.optString("name", "").trim().ifEmpty { id },
                version = obj.optString("version", "").trim().ifEmpty { "0" },
                category = category,
                description = obj.optString("description", "").trim(),
                binaries = parseBinaries(obj.optJSONArray("binaries")),
            ).also { it.raw = obj }
        }

        private fun parseBinaries(arr: JSONArray?): List<Binary> {
            if (arr == null) return emptyList()
            val out = mutableListOf<Binary>()
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is JSONObject -> {
                        val path = item.optString("path", "").trim()
                        if (path.isEmpty()) continue
                        out +=
                            Binary(
                                path = path,
                                mode = parseMode(item.optString("mode", ""), MODE_EXECUTABLE),
                                links = parseLinks(item.optJSONArray("links")),
                            )
                    }

                    is String -> {
                        if (item.isNotBlank()) out += Binary(item.trim(), MODE_EXECUTABLE)
                    }
                }
            }
            return out
        }

        /**
         * JSONArray → 非空字符串列表的单一实现（OCR low #474）：
         * stringList 与 parseLinks 曾各写一份，规则容易分叉。
         */
        private fun stringsOf(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val value = arr.optString(i, "").trim()
                if (value.isNotEmpty()) out += value
            }
            return out
        }

        /** 别名链接：多调用二进制以多个名字出现，每个名字都是释放后的路径。 */
        private fun parseLinks(arr: JSONArray?): List<String> = stringsOf(arr)

        /** 权限按八进制文本解析（清单里写 `"0755"`）；非法或超出 0777 时回退到默认值。 */
        internal fun parseMode(
            text: String,
            fallback: Int,
        ): Int {
            val raw = text.trim().removePrefix("0o").removePrefix("0O")
            if (raw.isEmpty()) return fallback
            val parsed = raw.toIntOrNull(8) ?: return fallback
            // 只允许 0..0777：负数与 setuid/setgid/sticky 位都不该由外部清单决定。
            return if (parsed in 0..0x1FF) parsed else fallback
        }
    }
}
