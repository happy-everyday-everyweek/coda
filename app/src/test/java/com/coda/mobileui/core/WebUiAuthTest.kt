package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web UI 访问凭据。
 *
 * 服务绑全部网卡且能驱动 agent，所以「请求带没带对的凭据」是安全边界，
 * 不能靠调用方自觉。
 */
class WebUiAuthTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `exact token matches`() {
        assertTrue(WebUiAuth.matches(token, token))
    }

    @Test
    fun `wrong or missing token does not match`() {
        assertFalse(WebUiAuth.matches(token, token.dropLast(1) + "0"))
        assertFalse(WebUiAuth.matches(token, null))
        assertFalse(WebUiAuth.matches(token, ""))
        assertFalse(WebUiAuth.matches(token, token.substring(1)))
    }

    @Test
    fun `empty expected never matches`() {
        assertFalse(WebUiAuth.matches("", ""))
        assertFalse(WebUiAuth.matches("", "x"))
    }

    @Test
    fun `bearer header is accepted`() {
        assertTrue(WebUiAuth.authorized(token, "Bearer $token", null, null))
        assertTrue(WebUiAuth.authorized(token, "bearer $token", null, null))
    }

    @Test
    fun `query token is accepted`() {
        assertTrue(WebUiAuth.authorized(token, null, token, null))
    }

    @Test
    fun `cookie is accepted and parsed among others`() {
        assertTrue(WebUiAuth.authorized(token, null, null, "a=1; ${WebUiAuth.COOKIE_NAME}=$token; b=2"))
        assertEquals("abc", WebUiAuth.cookie("x=1; y=abc", "y"))
        assertNull(WebUiAuth.cookie("x=1", "y"))
        assertNull(WebUiAuth.cookie(null, "y"))
    }

    @Test
    fun `request without any credential is rejected`() {
        assertFalse(WebUiAuth.authorized(token, null, null, null))
        assertFalse(WebUiAuth.authorized(token, "Bearer wrong", "wrong", "coda_webui=wrong"))
    }
}