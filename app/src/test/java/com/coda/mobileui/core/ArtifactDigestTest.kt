package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载产物的摘要校验。
 *
 * 关键约束是「缺摘要必须拒绝」：以前的实现只看大小与 ELF magic，
 * 一个被替换但同样大小的二进制照样会被 chmod 后执行。
 */
class ArtifactDigestTest {
    private val good = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test
    fun `matching sha256 passes`() {
        assertTrue(ArtifactDigest.matches(good, good))
        assertTrue(ArtifactDigest.matches(good.uppercase(), " $good "))
    }

    @Test
    fun `mismatching sha256 fails`() {
        assertFalse(ArtifactDigest.matches(good, other))
    }

    @Test
    fun `missing digest never passes`() {
        assertFalse(ArtifactDigest.matches(null, good))
        assertFalse(ArtifactDigest.matches("", good))
        assertFalse(ArtifactDigest.matches(good, null))
    }

    @Test
    fun `sha1 length is rejected`() {
        val sha1 = "c".repeat(40)
        assertNull(ArtifactDigest.normalize(sha1))
        assertFalse(ArtifactDigest.matches(sha1, sha1))
    }

    @Test
    fun `non hex and wrong length are rejected`() {
        assertNull(ArtifactDigest.normalize("z".repeat(64)))
        assertNull(ArtifactDigest.normalize("a".repeat(63)))
        assertNull(ArtifactDigest.normalize(null))
    }

    @Test
    fun `release asset digest is parsed`() {
        assertEquals(good, ArtifactDigest.fromReleaseAsset("sha256:$good"))
        assertEquals(good, ArtifactDigest.fromReleaseAsset("SHA256:${good.uppercase()}"))
        assertNull(ArtifactDigest.fromReleaseAsset("sha512:${"a".repeat(128)}"))
        assertNull(ArtifactDigest.fromReleaseAsset(null))
        assertNull(ArtifactDigest.fromReleaseAsset(""))
    }
}