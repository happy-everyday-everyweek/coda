package com.coda.mobileui.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 清单里的 id 与权限位都来自外部文件，必须按最窄的范围收紧。 */
class ExtensionManifestGuardTest {
    @Test
    fun dottedNamesAreNotUsableAsIds() {
        assertTrue(ExtensionManifest.isValidId("terminal"))
        assertTrue(ExtensionManifest.isValidId("terminal-2"))
        assertFalse(ExtensionManifest.isValidId("."))
        assertFalse(ExtensionManifest.isValidId(".."))
        assertFalse(ExtensionManifest.isValidId(".hidden"))
        assertFalse(ExtensionManifest.isValidId(""))
    }

    @Test
    fun permissionBitsStayWithinZeroTo0777() {
        assertEquals(493, ExtensionManifest.parseMode("0755", 420))
        assertEquals(511, ExtensionManifest.parseMode("0777", 420))
        assertEquals(420, ExtensionManifest.parseMode("0644", 493))
        // setuid / setgid / sticky：清单不该能设这些位
        assertEquals(420, ExtensionManifest.parseMode("04000", 420))
        assertEquals(420, ExtensionManifest.parseMode("0999", 420))
        assertEquals(420, ExtensionManifest.parseMode("abc", 420))
    }
}
