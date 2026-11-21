package com.coda.mobileui.core

/**
 * 更新的信任边界。
 *
 * 两条规则：
 * 清单元数据只能从官方域名取，镜像只能用来搬 APK 大文件；
 * 安装前必须比对签名证书指纹，签名不一致就不装。
 *
 * 原因是 manifest 与 sha256 同源就不可信：镜像可以同时改写 apkUrl、sha256 与 versionCode，
 * 客户端「校验自洽」照样通过。签名指纹是最后一道防线，它不来自网络。
 */
internal object UpdateTrust {
    /** 允许提供清单元数据的域名；其余一律只能用于下载已经带可信摘要的大文件。 */
    private val MANIFEST_HOSTS = setOf("github.com", "api.github.com", "raw.githubusercontent.com")

    /** 这个 URL 是否允许作为清单来源。 */
    fun trustedManifestUrl(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        // 明文 http 一律拒绝：中间人可以随意改写内容。
        if (parsed.scheme?.lowercase() != "https") return false
        return parsed.host?.lowercase() in MANIFEST_HOSTS
    }

    /** 规范化指纹：去掉冒号与空白、统一大写；不是 64 位十六进制时返回 null。 */
    fun normalizeFingerprint(raw: String?): String? {
        val text = raw?.filterNot { it == ':' || it.isWhitespace() }?.uppercase().orEmpty()
        if (text.length != 64) return null
        return text.takeIf { value -> value.all { it in '0'..'9' || it in 'A'..'F' } }
    }

    /**
     * 待安装包的签名者是否与期望一致。
     *
     * 期望为空时返回 false：没配指纹就不该悄悄放行，否则这道防线等于不存在。
     */
    fun signerMatches(
        expected: String?,
        actual: String?,
    ): Boolean {
        val want = normalizeFingerprint(expected) ?: return false
        val got = normalizeFingerprint(actual) ?: return false
        return want == got
    }
}
