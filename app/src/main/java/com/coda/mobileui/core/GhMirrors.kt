package com.coda.mobileui.core

/**
 * GitHub 加速镜像清单。
 *
 * 语义与 GitLink（happy-everyday-everyweek/gitlink 的 Mirror.kt）一致：
 * 最终 URL = prefix + 原始 GitHub 链接。清单按实测延迟排序，靠前的先试。
 *
 * 为什么把直连也放进来：镜像全挂时直连是最后一个可用选项，放在末尾而不是排除，
 * 否则网络好的用户反而下载不了。但直连对 /releases/download 大文件容易被限速，
 * 所以排最后。
 */
data class GhMirror(
    val id: String,
    val prefix: String,
    val note: String = "",
)

object GhMirrors {

    /** host 需要以 / 结尾，否则拼出来的 URL 会把主机名和路径粘在一起。 */
    private fun p(host: String): String = if (host.endsWith("/")) host else "$host/"

    /**
     * 内置清单。
     *
     * 前一段是实测可用、按延迟升序的镜像；后一段是历史上用过但近期观测异常的，
     * 保留在队尾——镜像会恢复，删掉反而要重新找。每条的 note 只写结论，
     * 不写具体毫秒数：延迟随网络变化，写死会误导。
     */
    val DEFAULT: List<GhMirror> = listOf(
        GhMirror("cxkpro", p("https://ghproxy.cxkpro.top"), "实测可用"),
        GhMirror("gh-proxy", p("https://gh-proxy.com"), "实测可用"),
        GhMirror("geekertao", p("https://ghfile.geekertao.top"), "实测可用"),
        GhMirror("nxnow", p("https://gh.nxnow.top"), "实测可用"),
        GhMirror("ghfast", p("https://ghfast.top"), "实测可用，大文件曾验证"),
        GhMirror("npee", p("https://down.npee.cn/?"), "实测可用"),
        GhMirror("jasonzeng", p("https://gh.jasonzeng.dev"), "实测可用"),
        GhMirror("chjina", p("https://gh.chjina.com"), "实测可用"),
        GhMirror("monlor", p("https://gh.monlor.com"), "实测可用"),
        GhMirror("ghproxynethyph", p("https://gh-proxy.net"), "实测可用"),
        GhMirror("ednovas", p("https://github.ednovas.xyz"), "实测可用"),
        GhMirror("ghproxynet", p("https://ghproxy.net"), "大流量长连接不稳"),
        GhMirror("zwy", p("https://gh.zwy.one"), "实测可用"),
        GhMirror("mrhjx", p("https://gitproxy.mrhjx.cn"), "实测可用"),
        GhMirror("bokimoe", p("https://github.boki.moe"), "实测可用"),
        GhMirror("fastgitcc", p("https://fastgit.cc"), "实测可用"),
        GhMirror("crashmc", p("https://cdn.crashmc.com"), "实测可用"),
        GhMirror("monkeyray", p("https://ghproxy.monkeyray.net"), "实测可用"),

        // ---- 历史上观测异常，留作后续重试 ----
        GhMirror("ghproxymirror", p("https://mirror.ghproxy.com"), "曾异常"),
        GhMirror("workers", p("https://github.abskoop.workers.dev"), "曾异常"),
        GhMirror("tbedu", p("https://github.tbedu.top"), "曾异常"),
        GhMirror("yylx", p("https://git.yylx.win"), "曾异常"),
        GhMirror("xxooo", p("https://gh.xxooo.cf"), "曾异常"),
        GhMirror("xx9527", p("https://gh.xx9527.cn"), "曾异常"),

        // ---- 直连兜底：放最后 ----
        GhMirror("github", p("https://github.com"), "官方直连"),
    )

    /**
     * 把一个 GitHub 原始链接套上镜像前缀。
     *
     * 直连那条不套前缀——它的 prefix 就是 github.com，再套一次会变成
     * `https://github.com/https://github.com/...`。
     */
    fun wrap(prefix: String, rawUrl: String): String =
        if (prefix.contains("github.com/") && rawUrl.startsWith("https://github.com/")) rawUrl
        else prefix + rawUrl
}