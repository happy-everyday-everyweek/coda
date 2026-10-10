package com.coda.mobileui.ext

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
    /** 原始清单，供各分类读取自己的专属段。 */
    val raw: JSONObject,
) {

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
    fun stringList(key: String): List<String> {
        val arr: JSONArray = raw.optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i, "").trim()
            if (value.isNotEmpty()) out += value
        }
        return out
    }

    companion object {

        /** 释放权限：0755（Kotlin 不支持八进制字面量，写成十进制 493）。 */
        const val MODE_EXECUTABLE = 493

        /** 释放权限：0644。 */
        const val MODE_DEFAULT = 420

        /** id 用作目录名与存储键，限制在安全字符集内。 */
        fun isValidId(id: String): Boolean =
            id.isNotEmpty() && id.length <= 96 &&
                id.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

        /** 解析清单；id 或分类缺失即视为非法清单。 */
        fun parse(text: String): ExtensionManifest? {
            val obj = try {
                JSONObject(text)
            } catch (_: Throwable) {
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
                raw = obj,
            )
        }

        private fun parseBinaries(arr: JSONArray?): List<Binary> {
            if (arr == null) return emptyList()
            val out = mutableListOf<Binary>()
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is JSONObject -> {
                        val path = item.optString("path", "").trim()
                        if (path.isEmpty()) continue
                        out += Binary(
                            path = path,
                            mode = parseMode(item.optString("mode", ""), MODE_EXECUTABLE),
                            links = parseLinks(item.optJSONArray("links")),
                        )
                    }
                    is String -> if (item.isNotBlank()) out += Binary(item.trim(), MODE_EXECUTABLE)
                }
            }
            return out
        }

        /** 别名链接：多调用二进制以多个名字出现，每个名字都是释放后的路径。 */
        private fun parseLinks(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val value = arr.optString(i, "").trim()
                if (value.isNotEmpty()) out += value
            }
            return out
        }

        /** 权限按八进制文本解析（清单里写 `"0755"`）；非法时回退到默认值。 */
        private fun parseMode(text: String, fallback: Int): Int {
            val raw = text.trim().removePrefix("0o").removePrefix("0O")
            if (raw.isEmpty()) return fallback
            return raw.toIntOrNull(8) ?: fallback
        }
    }
}
