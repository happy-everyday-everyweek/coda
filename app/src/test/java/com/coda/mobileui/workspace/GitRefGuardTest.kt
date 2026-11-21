package com.coda.mobileui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传给 git 的参数校验。
 *
 * 分支名与远端地址都可能来自仓库内容或界面输入；以 `-` 开头的值会被 git 当成选项，
 * 能改配置甚至执行外部命令，必须在调用前挡掉。
 */
class GitRefGuardTest {
    @Test
    fun rejectsOptionLikeRefs() {
        assertFalse(Git.isSafeRef("--orphan"))
        assertFalse(Git.isSafeRef("-d"))
    }

    @Test
    fun rejectsEmptyAndParentTraversal() {
        assertFalse(Git.isSafeRef(""))
        assertFalse(Git.isSafeRef("feature/../../etc"))
        assertFalse(Git.isSafeRef("a//b"))
    }

    @Test
    fun rejectsControlAndRevisionSyntax() {
        assertFalse(Git.isSafeRef("feature\nname"))
        assertFalse(Git.isSafeRef("head~1"))
        assertFalse(Git.isSafeRef("a:b"))
    }

    @Test
    fun acceptsNormalBranchNames() {
        assertTrue(Git.isSafeRef("main"))
        assertTrue(Git.isSafeRef("feature/login-flow"))
        assertTrue(Git.isSafeRef("release/2026.01"))
        assertTrue(Git.isSafeRef("origin/main"))
    }

    @Test
    fun rejectsOptionLikeUrls() {
        assertFalse(Git.isSafeUrl("--upload-pack=/bin/sh"))
        assertFalse(Git.isSafeUrl(""))
        assertFalse(Git.isSafeUrl("https://example.com/a b"))
    }

    @Test
    fun acceptsNormalUrls() {
        assertTrue(Git.isSafeUrl("https://github.com/owner/repo.git"))
        assertTrue(Git.isSafeUrl("git@github.com:owner/repo.git"))
    }
}