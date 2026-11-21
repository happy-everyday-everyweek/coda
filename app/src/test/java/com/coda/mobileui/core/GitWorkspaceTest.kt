package com.coda.mobileui.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 用真实的目录结构验证 worktree / submodule 的根路径与远端解析。 */
class GitWorkspaceTest {
    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("gitws").toFile()
        GitWorkspace.invalidate()
    }

    @After
    fun tearDown() {
        GitWorkspace.invalidate()
        tmp.deleteRecursively()
    }

    @Test
    fun plainRepositoryUsesTheDirectoryHoldingDotGit() {
        val repo = File(tmp, "plain")
        File(repo, ".git").mkdirs()
        File(repo, ".git/HEAD").writeText("ref: refs/heads/main\n")
        File(repo, ".git/config").writeText(remoteConfig("https://github.com/acme/plain.git"))

        val info = GitWorkspace.inspect(File(repo, "src").path)
        assertEquals(repo.absolutePath, info?.root)
        assertEquals("main", info?.branch)
        assertEquals("acme", info?.owner)
        assertEquals("plain", info?.repo)
    }

    @Test
    fun worktreeResolvesRootToTheWorkingTreeAndConfigToTheCommonDir() {
        val main = File(tmp, "main")
        val wtGit = File(main, ".git/worktrees/feature")
        wtGit.mkdirs()
        File(wtGit, "HEAD").writeText("ref: refs/heads/feature\n")
        File(wtGit, "commondir").writeText("../..\n")
        File(main, ".git/config").writeText(remoteConfig("git@github.com:acme/app.git"))

        val worktree = File(tmp, "wt")
        File(worktree, "src").mkdirs()
        File(worktree, ".git").writeText("gitdir: ${wtGit.path}\n")

        val info = GitWorkspace.inspect(File(worktree, "src").path)
        assertEquals(worktree.absolutePath, info?.root)
        assertEquals("feature", info?.branch)
        assertEquals("acme", info?.owner)
        assertEquals("app", info?.repo)
    }

    @Test
    fun directoryOutsideAnyRepositoryHasNoInfo() {
        val outside = File(tmp, "outside")
        outside.mkdirs()
        assertNull(GitWorkspace.inspect(outside.path))
    }

    private fun remoteConfig(url: String): String =
        """
        [core]
            bare = false
        [remote "origin"]
            url = $url
            fetch = +refs/heads/*:refs/remotes/origin/*
        """.trimIndent()
}
