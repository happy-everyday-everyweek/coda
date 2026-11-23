package com.coda.mobileui

/**
 * 预热失败时该落哪一份空快照（#751）。
 *
 * `SettingsData.prewarm` 的 KDoc 承诺「读取异常一律收进日志并落一份空结果」，
 * 但实现只打了日志。持续失败时（provider_config.json 损坏、HooksCore.discover 抛异常）
 * `hasSnapshot` 永远为 false，于是
 * ensureSnapshot → prewarm → onDone → renderPage → ensureSnapshot
 * 成了没有退避的后台 IO/渲染死循环，页面却一直停在「正在读取」，还持续耗电。
 *
 * 键必须与 [SettingsData.hasSnapshot] 查的完全一致，否则落了也白落。
 */
internal object SnapshotFallback {
    /**
     * 最近一次各键的失败原因（OCR medium）。
     *
     * 非 hooks 键的空快照与「真的没配置」不可区分：provider_config.json 损坏时
     * 页面显示「0 个供应商」，用户以为配置丢了、在坏文件上重建配置。
     * 页面构建器查这里，非空就先渲染一行错误说明。进程内存即可：
     * 重启后 prewarm 会重跑，失败会再记。
     */
    private val reasons = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 该键最近一次预热失败的原因；null 表示没有失败记录。 */
    fun reasonFor(key: String): String? = reasons[key]

    /** 预热成功后清掉失败记录，避免错误行一直挂着。 */
    fun clearReason(key: String) {
        reasons.remove(key)
    }

    /** 返回「键 → 空值」；未知键或无从构造键时返回 null。 */
    fun emptyFor(
        key: String,
        workspacePath: String?,
        reason: String = "读取失败",
    ): Pair<String, Any>? {
        reasons[key] = reason
        return emptyValueFor(key, workspacePath, reason)
    }

    private fun emptyValueFor(
        key: String,
        workspacePath: String?,
        reason: String,
    ): Pair<String, Any>? =
        when {
            key == "providers" -> "providers" to emptyList<Any>()
            key.startsWith("assets:") -> key to emptyList<Any>()
            key == "automations" -> "automations" to emptyList<Any>()
            key == "sysprompts" -> "sysprompts" to emptyMap<String, String>()
            key.startsWith("hooks:") -> key to emptyHooks(key.removePrefix("hooks:"), reason)
            key == "hooks" -> workspacePath?.let { "hooks:$it" to emptyHooks(it, reason) }
            else -> null
        }

    /**
     * hooks 的空快照。
     *
     * 用真实类型的空实例而不是空列表：页面构建器会按 HooksCore.Snapshot 取字段，
     * 塞错类型会在渲染时抛 ClassCastException，把一个「加载失败」变成崩溃。
     * 失败原因放进 errors，页面能显示出来而不是假装「没有钩子」。
     */
    private fun emptyHooks(
        workspacePath: String,
        reason: String,
    ): Any =
        com.coda.mobileui.core.HooksCore.Snapshot(
            workspacePath = workspacePath,
            projectSources = emptyList(),
            userSource = null,
            runtimeRoot =
                com.coda.mobileui.core.HooksCore
                    .RuntimeRoot(false, 0L, 0L),
            entries = emptyList(),
            userEntries = emptyList(),
            bundleDigest = null,
            errors = listOf(reason),
        )
}
