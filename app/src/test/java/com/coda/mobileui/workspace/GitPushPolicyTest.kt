package com.coda.mobileui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无上游时该不该自动 --set-upstream（#783）。
 *
 * 原实现取第一个 remote：私有 fork 与 upstream 并存时，本地提交会被推到
 * 恰好排在前面的那个远端，属于不可逆的数据外泄。detached HEAD 下 branch()
 * 返回短 hash，拿它当分支名还会拼出一个无意义的 refspec。
 */
class GitPushPolicyTest {
    @Test
    fun `只有一个远端时自动设置上游`() {
        val d =
            GitPushPolicy.decide(
                firstError = "fatal: The current branch x has no upstream branch",
                remotes = listOf("origin"),
                branch = "main",
            )
        assertEquals("origin", d.remote)
        assertEquals("main", d.branch)
    }

    @Test
    fun `多个远端时不自动推送`() {
        val d = GitPushPolicy.decide(firstError = "no upstream branch", remotes = listOf("origin", "upstream"), branch = "main")
        assertNull("多远端必须让用户自己选", d.remote)
        assertTrue(d.reason.isNotEmpty())
    }

    @Test
    fun `没有远端时不自动推送`() {
        val d = GitPushPolicy.decide(firstError = "no upstream branch", remotes = emptyList(), branch = "main")
        assertNull(d.remote)
    }

    @Test
    fun `错误不是缺上游时不处理`() {
        val d = GitPushPolicy.decide(firstError = "rejected: non-fast-forward", remotes = listOf("origin"), branch = "main")
        assertNull(d.remote)
    }

    @Test
    fun `detached HEAD 时不自动推送`() {
        // detached 由调用方从 symbolic-ref 的成败得知（branchOrNull 返回 null），
        // 不再靠字符串猜。
        val d = GitPushPolicy.decide(firstError = "no upstream branch", remotes = listOf("origin"), branch = null)
        assertNull("detached HEAD 不能拼 refspec", d.remote)
        assertTrue(d.reason.contains("detached") || d.reason.contains("具名分支"))
    }

    @Test
    fun `纯十六进制的合法分支名不被误杀`() {
        // deadbeef、cafe123 都是合法分支名；旧实现按 hex 形状猜 detached，
        // 把这些分支的推送直接挡死（OCR medium）。
        val d = GitPushPolicy.decide("no upstream branch", listOf("origin"), "deadbeef")
        assertEquals("origin", d.remote)
        assertEquals("deadbeef", d.branch)
        assertEquals("origin", GitPushPolicy.decide("no upstream branch", listOf("origin"), "cafe123").remote)
    }

    @Test
    fun `空分支名不处理`() {
        assertNull(GitPushPolicy.decide("no upstream branch", listOf("origin"), "").remote)
    }

    @Test
    fun `带斜杠的分支名可用`() {
        val d = GitPushPolicy.decide("no upstream branch", listOf("origin"), "feature/login")
        assertEquals("feature/login", d.branch)
    }

    @Test
    fun `以横线开头的远端名被拒`() {
        assertNull(GitPushPolicy.decide("no upstream branch", listOf("--upload-pack=evil"), "main").remote)
    }

    @Test
    fun `远端列表忽略空行`() {
        val d = GitPushPolicy.decide("no upstream branch", listOf("", "origin", "  "), "main")
        assertEquals("origin", d.remote)
    }
}
