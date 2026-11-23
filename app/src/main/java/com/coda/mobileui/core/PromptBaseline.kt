package com.coda.mobileui.core

import org.json.JSONObject

/**
 * 系统提示词模板的状态判定（#742）。
 *
 * 旧实现把「第一次见到的内容」当基线：任何在设置页首次打开之前发生的修改
 * （内核侧编辑、备份恢复、从无基线版本升级）都会被记成默认，真实改动被永久隐藏。
 *
 * 现在基线由**释放模板的一方**给出：内核 ensureSystemPromptFiles 会在模板目录写
 * `.template-baselines.json`，内容是「文件名 → 默认模板 sha256」。客户端只比对，
 * 不再猜，也不再在只读的状态查询里写 SharedPreferences。
 *
 * 清单缺失时（内核还没跑过、或写失败）返回「待内核初始化」而不是「默认」：
 * 把未知说成默认，正是这条 Issue 要消除的误报。
 */
internal object PromptBaseline {
    /** 内核写出的清单文件名，与 bootstrap 侧 SYSTEM_PROMPT_BASELINE_FILE 一致。 */
    const val MANIFEST = ".template-baselines.json"

    /** 一份模板的显示状态。 */
    enum class Status {
        /** 内核尚未释放模板：文件不存在。 */
        DEFAULT_ABSENT,

        /** 内容与内核默认模板一致。 */
        DEFAULT,

        /** 用户改过。 */
        MODIFIED,

        /** 用户主动清空。 */
        CLEARED,

        /** 文件在但读不出来。 */
        UNREADABLE,

        /** 清单缺失，无从判断是否默认。 */
        UNKNOWN,
    }

    /** 状态文案。界面直接显示，不再各自拼字符串。 */
    fun label(s: Status): String =
        when (s) {
            Status.DEFAULT_ABSENT, Status.DEFAULT -> "默认"
            Status.MODIFIED -> "已修改"
            Status.CLEARED -> "已清空"
            Status.UNREADABLE -> "读取失败"
            Status.UNKNOWN -> "待内核初始化"
        }

    /**
     * 解析清单。
     *
     * 坏 JSON、空对象一律返回空表：调用方据此落到 [Status.UNKNOWN]，
     * 而不是把每一项都当成默认。
     */
    fun parseManifest(text: String?): Map<String, Set<String>> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(text)
            val out = HashMap<String, Set<String>>()
            o.keys().forEach { k ->
                if (k.isEmpty()) return@forEach
                // 清单记的是「历次默认版本的摘要」：内核只补缺失文件，升级后磁盘上
                // 仍可能是旧版本释放的默认内容，只比对当前版本会把它们误报成已修改。
                // 同时兼容早期「文件名 → 单个摘要」的写法。
                val arr = o.optJSONArray(k)
                val set = LinkedHashSet<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "")
                        if (v.isNotEmpty()) set.add(v.lowercase())
                    }
                } else {
                    val v = o.optString(k, "")
                    if (v.isNotEmpty()) set.add(v.lowercase())
                }
                if (set.isNotEmpty()) out[k] = set
            }
            out.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * 判定单个文件的状态。
     *
     * @param exists 文件是否存在
     * @param text 文件内容；null 表示读失败
     * @param digest 内容摘要；调用方算好后传入，便于纯逻辑单测
     * @param baseline 清单里这份文件的默认摘要；null 表示清单没给出
     */
    fun statusOf(
        exists: Boolean,
        text: String?,
        digest: String?,
        baselines: Set<String>?,
    ): Status {
        if (!exists) return Status.DEFAULT_ABSENT
        if (text == null) return Status.UNREADABLE
        if (baselines.isNullOrEmpty() || digest.isNullOrBlank()) return Status.UNKNOWN
        // 命中任一历史默认版本都算「默认」：文件可能是旧版内核释放后一直没动过。
        if (baselines.any { it.equals(digest, ignoreCase = true) }) return Status.DEFAULT
        // 纯空白也算改动：用户主动清空与从未编辑是两回事。
        return if (text.isBlank()) Status.CLEARED else Status.MODIFIED
    }

    /** 文本摘要，与内核侧 `sha256(utf8(body))` 一致。 */
    fun sha256(text: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
