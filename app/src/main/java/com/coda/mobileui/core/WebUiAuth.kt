package com.coda.mobileui.core

/**
 * Web UI 的访问凭据校验。
 *
 * 这个服务绑定全部网卡：同一局域网里任何设备都能访问 `/api/state`（完整会话）与
 * `/api/send`（驱动 agent 执行工具）。所以每个请求都必须带凭据，凭据三选一：
 * `Authorization: Bearer`、查询参数 `?token=`、或首次访问后下发的 cookie。
 */
internal object WebUiAuth {
    /** 下发到浏览器的 cookie 名。 */
    const val COOKIE_NAME = "coda_webui"

    /** 首次访问时用来换 cookie 的查询参数。 */
    const val QUERY = "token"

    /**
     * 常量时间比较。
     *
     * 逐字节异或后统一比较，不按前缀提前返回：提前返回会把「前面几位对了」变成可观测的信号。
     */
    fun matches(
        expected: String,
        provided: String?,
    ): Boolean {
        if (expected.isEmpty() || provided.isNullOrEmpty()) return false
        if (expected.length != provided.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor provided[i].code)
        return diff == 0
    }

    /** 从 Cookie 请求头里取指定名字的值。 */
    fun cookie(
        header: String?,
        name: String,
    ): String? {
        if (header.isNullOrBlank()) return null
        return header
            .split(';')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.firstOrNull { it.first == name }
            ?.second
            ?.takeIf { it.isNotEmpty() }
    }

    /** 三个来源任一命中即视为已授权。 */
    fun authorized(
        expected: String,
        authorization: String?,
        queryToken: String?,
        cookieHeader: String?,
    ): Boolean {
        val bearer =
            authorization
                ?.trim()
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring("Bearer ".length)
                ?.trim()
        return matches(expected, bearer) ||
            matches(expected, queryToken) ||
            matches(expected, cookie(cookieHeader, COOKIE_NAME))
    }
}
