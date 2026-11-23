package com.coda.mobileui.core

/**
 * 分块下载的断点采纳与响应分类（#809、#814、#815）。
 *
 * 单独抽出来是因为这两件事都错得很隐蔽：
 *
 * 分块的 part 文件被预分配到全长，`length() == total` 只说明「容器够大」，
 * 不说明字节都在。缓存清理把它删掉或截短后，重新 setLength 会把空洞补成 0，
 * 而 done[] 仍声称那些字节已完成——已完成的块被整块跳过，零洞被缝进成品，
 * 长度校验完全通不出问题，只有 sha256 能发现，而 sha256 是可选的。
 *
 * 块请求收到 200 表示这个源忽略了 Range（或 If-Range 判定远端已变），
 * 绝不能按 [DownloadOutcome.ofHttp] 归成 OK：那会让上层跳过失败判断，
 * 直接把一个几乎全是 0 的预分配文件校验并上线。
 */
internal object ChunkResume {
    /**
     * 这份分块断点能不能采纳。
     *
     * [partExists] 与 [partBytes] 必须由调用方从磁盘现取，不能沿用记录里的值：
     * 记录写于上次运行，这中间系统完全可能清过缓存。
     */
    fun adopt(
        saved: ResumeState.Companion.ChunkState?,
        url: String,
        total: Long,
        chunks: List<ChunkPlan.Chunk>,
        partExists: Boolean,
        partBytes: Long,
        etag: String?,
        lastModified: String?,
    ): Boolean {
        if (saved == null) return false
        if (saved.url != url || saved.total != total || saved.ranges != chunks) return false
        // 文件必须在，且长度正好等于总长：短了说明字节丢了，长了说明这不是本次的文件。
        if (!partExists || partBytes != total) return false
        val hadValidator = !saved.etag.isNullOrBlank() || !saved.lastModified.isNullOrBlank()
        val hasValidator = !etag.isNullOrBlank() || !lastModified.isNullOrBlank()
        if (!hadValidator || !hasValidator) return false
        // 两边都有的那项必须一致；只有一边有就无从比对，按已知项判断。
        if (!saved.etag.isNullOrBlank() && !etag.isNullOrBlank() && saved.etag != etag) return false
        if (!saved.lastModified.isNullOrBlank() && !lastModified.isNullOrBlank() &&
            saved.lastModified != lastModified
        ) {
            return false
        }
        return true
    }

    /**
     * 块响应的分类：只有 206 才是成功。
     *
     * 其余 2xx 归 IO（语义是「这个源不支持 Range」），让调用方换源或退回单连接，
     * 而不是把预分配的零文件当成品。4xx/5xx 仍按 HTTP 语义分类。
     */
    fun chunkHttpKind(code: Int): DownloadOutcome.Kind =
        when {
            code == 206 -> DownloadOutcome.Kind.OK
            code in 200..299 -> DownloadOutcome.Kind.IO
            else -> DownloadOutcome.ofHttp(code)
        }
}
