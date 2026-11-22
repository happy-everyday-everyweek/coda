package com.coda.mobileui.core

/**
 * 下载结果的分类判定（#729）。
 *
 * 之前 AssetFetcher 与 UpdateChecker 各自把失败压成一个 boolean，
 * 「镜像拒绝」「文件不存在」「镜像挂了」「校验不过」在调用方看来长得一样，
 * 于是换镜像策略只能一刀切。这里把分类抽成纯逻辑，可以直接单测。
 */
internal object DownloadOutcome {
    /** 失败类别。调用方据此决定换镜像、重试还是直接报错。 */
    enum class Kind {
        OK,

        /** 401/403：镜像拒绝，换下一个源。 */
        REJECTED,

        /** 404：这个源上没有该文件，换下一个源。 */
        MISSING,

        /** 5xx：镜像自己挂了，换下一个源并给它记一次失败。 */
        SERVER_ERROR,

        /** 其它非 2xx：按未知 HTTP 失败处理。 */
        HTTP_OTHER,

        /** 连接重置、TLS 失败、磁盘写满等 IO 问题。 */
        IO,

        /** 长度或 sha256 不符：内容坏了，必须换源而不是重试同一个。 */
        VERIFY_FAILED,

        /**
         * 内容已校验通过，只是没能写到目标位置（目标是目录、跨设备、只读等）。
         * 换源重下解决不了本地写入问题，调用方应保留半成品并重试就位。
         */
        PLACE_FAILED,

        /** 用户取消：不是失败，不应触发换源循环。 */
        CANCELLED,
    }

    fun ofHttp(code: Int): Kind =
        when {
            code in 200..299 -> Kind.OK
            code == 401 || code == 403 -> Kind.REJECTED
            code == 404 -> Kind.MISSING
            code in 500..599 -> Kind.SERVER_ERROR
            else -> Kind.HTTP_OTHER
        }

    /**
     * 内容校验：期望值 <= 0 或空串表示「调用方没给」，跳过该项。
     *
     * 大小与 sha256 都通过才算 OK；任一不符都是 VERIFY_FAILED，
     * 不能因为「大小对了」就放过 hash 不符的文件。
     */
    fun verify(
        actualSize: Long,
        expectedSize: Long,
        actualSha256: String?,
        expectedSha256: String?,
    ): Kind {
        if (expectedSize > 0 && actualSize != expectedSize) return Kind.VERIFY_FAILED
        if (!expectedSha256.isNullOrBlank()) {
            if (actualSha256.isNullOrBlank()) return Kind.VERIFY_FAILED
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) return Kind.VERIFY_FAILED
        }
        return Kind.OK
    }

    /**
     * 这个类别是否应该换下一个镜像源。
     *
     * 取消不换，避免用户退出后循环继续跑；写入失败也不换，
     * 内容已经校验通过，再下一个源同样写不进去，只是白耗几十 MB 流量。
     */
    fun shouldTryNextMirror(kind: Kind): Boolean =
        when (kind) {
            Kind.OK, Kind.CANCELLED, Kind.PLACE_FAILED -> false
            else -> true
        }
}