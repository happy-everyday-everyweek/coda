package com.coda.mobileui.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端分类的纯判定测试。
 *
 * 覆盖 #483 方言白名单与大小写归一、#480 Windows 风格绝对路径识别。
 * 这两处都不依赖 Android 类型，可以直接在 JVM 上验证。
 */
class TerminalCategorySpecTest {
    // ---------------------------------------------------------------- #483 方言
    @Test
    fun knownDialectsPassThrough() {
        assertEquals("posix", TerminalCategory.normalizeDialect("posix"))
        assertEquals("git-bash", TerminalCategory.normalizeDialect("git-bash"))
        assertEquals("cmd", TerminalCategory.normalizeDialect("cmd"))
    }

    @Test
    fun dialectIsLowercasedAndTrimmed() {
        assertEquals("git-bash", TerminalCategory.normalizeDialect("  Git-Bash  "))
        assertEquals("cmd", TerminalCategory.normalizeDialect("CMD"))
    }

    @Test
    fun emptyDialectFallsBackToDefault() {
        assertEquals(TerminalCategory.DEFAULT_DIALECT, TerminalCategory.normalizeDialect(""))
        assertEquals(TerminalCategory.DEFAULT_DIALECT, TerminalCategory.normalizeDialect("   "))
    }

    @Test
    fun unknownDialectFallsBackInsteadOfPassingThrough() {
        // bash 不是约定取值：原实现会原样注入 ZCODE_TERMINAL_DIALECT。
        assertEquals(TerminalCategory.DEFAULT_DIALECT, TerminalCategory.normalizeDialect("bash"))
        assertEquals(TerminalCategory.DEFAULT_DIALECT, TerminalCategory.normalizeDialect("zsh"))
        assertEquals(TerminalCategory.DEFAULT_DIALECT, TerminalCategory.normalizeDialect("powershell"))
    }

    // ---------------------------------------------------------------- #480 路径
    @Test
    fun posixAbsoluteIsRecognised() {
        assertTrue(TerminalCategory.isAbsoluteSpec("/system/bin/sh"))
        assertTrue(TerminalCategory.isAbsoluteSpec("  /bin/sh  "))
    }

    @Test
    fun windowsDriveLettersAreAbsolute() {
        assertTrue(TerminalCategory.isAbsoluteSpec("C:\\tools\\sh.exe"))
        assertTrue(TerminalCategory.isAbsoluteSpec("C:/tools/sh.exe"))
        assertTrue(TerminalCategory.isAbsoluteSpec("d:\\x"))
    }

    @Test
    fun uncPathsAreAbsolute() {
        assertTrue(TerminalCategory.isAbsoluteSpec("\\\\server\\share\\sh.exe"))
    }

    @Test
    fun relativeSpecsStayRelative() {
        assertFalse(TerminalCategory.isAbsoluteSpec("bin/sh"))
        assertFalse(TerminalCategory.isAbsoluteSpec("./bin/sh"))
        assertFalse(TerminalCategory.isAbsoluteSpec("sh"))
        // 只有盘符没有分隔符不算绝对路径，避免把 "a:b" 这类怪名字误判
        assertFalse(TerminalCategory.isAbsoluteSpec("a:b"))
        assertFalse(TerminalCategory.isAbsoluteSpec(""))
    }
}