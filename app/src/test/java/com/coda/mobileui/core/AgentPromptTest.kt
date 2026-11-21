package com.coda.mobileui.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 提示词落盘：覆盖要原子，空内容要删文件，失败不能静默。 */
class AgentPromptTest {
    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("prompt").toFile()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun saveThenReadRoundTrips() {
        val file = File(tmp, "AGENTS.md")
        assertNull(AgentPrompt.save(file, "第一行\r\n第二行"))
        assertEquals("第一行\n第二行", AgentPrompt.read(file))
    }

    @Test
    fun overwriteReplacesWholeFileAndLeavesNoTempFiles() {
        val file = File(tmp, "AGENTS.md")
        assertNull(AgentPrompt.save(file, "old content that is long"))
        assertNull(AgentPrompt.save(file, "new"))
        assertEquals("new", AgentPrompt.read(file))
        assertFalse(tmp.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun blankContentRemovesTheFile() {
        val file = File(tmp, "AGENTS.md")
        assertNull(AgentPrompt.save(file, "text"))
        assertTrue(file.isFile)
        assertNull(AgentPrompt.save(file, "   \n  "))
        assertFalse(file.exists())
    }
}
