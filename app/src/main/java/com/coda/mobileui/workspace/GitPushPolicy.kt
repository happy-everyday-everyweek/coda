package com.coda.mobileui.workspace

/**
 * 无上游分支时的推送决策（#783）。
 *
 * 原实现在 `push` 报「no upstream branch」后取**第一个** remote 补
 * `--set-upstream`。仓库同时配了私有 fork 与 upstream 时，本地提交会被推到
 * 恰好排在前面的那个远端——不可逆的数据外泄，而 KDoc 里「唯一的远端」这个前提
 * 从来没有被校验。
 *
 * 现在只有**恰好一个**远端时才自动设置上游，其余情况原样返回错误让用户自己选。
 * 另外 detached HEAD 下 `branch()` 返回的是短 hash，拿它当分支名会拼出无意义的
 * refspec，这里一并拒掉。
 */
internal object GitPushPolicy {
    /** 决策结果。[remote] 非空表示可以自动 `--set-upstream`。 */
    data class Decision(
        val remote: String?,
        val branch: String,
        val reason: String,
    )

    fun decide(
        firstError: String,
        remotes: List<String>,
        branch: String,
    ): Decision {
        if (!firstError.contains("no upstream branch")) {
            return Decision(null, branch, "不是缺少上游分支的错误")
        }
        val names = remotes.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return Decision(null, branch, "没有配置远端")
        if (names.size > 1) {
            return Decision(null, branch, "配置了多个远端（${names.joinToString("、")}），请自行选择推送目标")
        }
        val remote = names[0]
        if (!safeRef(remote)) return Decision(null, branch, "远端名不合法：$remote")
        if (!symbolicBranch(branch)) return Decision(null, branch, "当前不在具名分支上（可能是 detached HEAD）")
        if (!safeRef(branch)) return Decision(null, branch, "分支名不合法：$branch")
        return Decision(remote, branch, "")
    }

    /**
     * 是否是具名分支而不是 detached HEAD 的短 hash。
     *
     * git 的短 hash 是 7 位以上的纯十六进制；分支名允许字母数字与 `/_-.`，
     * 但一个纯十六进制且长度像 hash 的名字无法与 detached 状态区分，宁可拒绝。
     */
    internal fun symbolicBranch(name: String): Boolean {
        if (name.isEmpty()) return false
        val looksLikeHash = name.length in 7..40 && name.all { it in "0123456789abcdef" }
        return !looksLikeHash
    }

    /** 引用名不得以 `-` 开头（会被 git 当选项），也不得含空白或 shell 元字符。 */
    private fun safeRef(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("-")) return false
        return name.none { it.isWhitespace() || it in ";|&$`\\\"'<>()*?[]{}~^:" }
    }
}
