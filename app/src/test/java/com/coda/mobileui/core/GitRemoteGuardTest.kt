package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * git config 远端解析的守卫。
 *
 * 对应 issue：#274（origin 优先、空 url 行跳过）。
 * #273（损坏 .git 文件继续向上找）由 GitWorkspaceTest 的真实目录用例覆盖。
 */
class GitRemoteGuardTest {
    @Test
    fun originWinsEvenWhenNotFirst() {
        val config =
            """
            [remote "upstream"]
                url = https://github.com/other/repo.git
            [remote "origin"]
                url = git@github.com:acme/app.git
            """.trimIndent()
        assertEquals("git@github.com:acme/app.git", GitWorkspace.parseRemoteUrl(config))
    }

    @Test
    fun emptyUrlLineIsSkippedNotFatal() {
        val config =
            """
            [remote "origin"]
                url =
                fetch = +refs/heads/*:refs/remotes/origin/*
            [remote "backup"]
                url = https://github.com/acme/mirror.git
            """.trimIndent()
        assertEquals("https://github.com/acme/mirror.git", GitWorkspace.parseRemoteUrl(config))
    }

    @Test
    fun firstRemoteUsedWhenNoOrigin() {
        val config =
            """
            [remote "fork"]
                url = https://github.com/me/app.git
            """.trimIndent()
        assertEquals("https://github.com/me/app.git", GitWorkspace.parseRemoteUrl(config))
    }

    @Test
    fun noRemotesReturnsNull() {
        assertNull(GitWorkspace.parseRemoteUrl("[core]\n    bare = false\n"))
    }
}