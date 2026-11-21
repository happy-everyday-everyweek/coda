package com.coda.mobileui.core

/**
 * 下载产物的摘要校验。
 *
 * 主机二进制与插件都是下载后直接 `chmod 0755` 执行的，只看文件大小和 ELF magic
 * 挡不住被替换的内容。这里只负责一件事：把期望摘要与实际摘要做严格比对，
 * 缺摘要、格式不对、长度不对都视为不可信，而不是放行。
 */
internal object ArtifactDigest {
    /** SHA-256 的十六进制长度。 */
    private const val HEX_LEN = 64

    /** SHA-1 只有 40 位十六进制，存在碰撞风险，不能用于内容校验。 */
    private const val SHA1_LEN = 40

    /** 规范化：去掉空白、统一小写；不是合法 SHA-256 时返回 null。 */
    fun normalize(raw: String?): String? {
        val text = raw?.trim()?.lowercase().orEmpty()
        if (text.isEmpty() || text.length == SHA1_LEN) return null
        if (text.length != HEX_LEN) return null
        return text.takeIf { value -> value.all { it in '0'..'9' || it in 'a'..'f' } }
    }

    /**
     * 从 GitHub Release 的 asset JSON 里取摘要。
     *
     * 上游 API 会带 `digest` 字段（形如 `sha256:abcd...`）；没有就是没有，
     * 不能用空值兜底成「校验通过」。
     */
    fun fromReleaseAsset(digestField: String?): String? {
        val text = digestField?.trim()?.lowercase().orEmpty()
        if (!text.startsWith("sha256:")) return null
        return normalize(text.removePrefix("sha256:"))
    }

    /** 严格比对：期望缺失或与 [SHA1_LEN] 长度相同时一律拒绝。 */
    fun matches(
        expected: String?,
        actual: String?,
    ): Boolean {
        val want = normalize(expected) ?: return false
        val got = normalize(actual) ?: return false
        var diff = 0
        for (i in want.indices) diff = diff or (want[i].code xor got[i].code)
        return diff == 0
    }
}
