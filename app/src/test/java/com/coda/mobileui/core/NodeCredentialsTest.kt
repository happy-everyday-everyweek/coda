package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeCredentialsTest {
    private val codec = NodeCredentials()

    @Test
    fun issuedCredentialSurvivesJsonRoundTrip() {
        val (list, credential) = codec.issue(emptyList(), "笔记本", 1000L)
        val restored = codec.fromJson(codec.toJson(list))
        assertEquals(1, restored.size)
        assertEquals(credential.id, restored[0].id)
        assertEquals("笔记本", restored[0].label)
        assertEquals(credential.token, restored[0].token)
        assertEquals(1000L, restored[0].createdAt)
    }

    @Test
    fun revokedCredentialNoLongerMatchesToken() {
        val (list, credential) = codec.issue(emptyList(), "笔记本", 1000L)
        assertEquals(credential.id, codec.findByToken(list, credential.token)?.id)
        val revoked = codec.revoke(list, credential.id)
        assertNull(codec.findByToken(revoked, credential.token))
        assertTrue(revoked[0].revoked)
    }

    @Test
    fun revokingOneCredentialKeepsTheOther() {
        val (first, laptop) = codec.issue(emptyList(), "笔记本", 1000L)
        val (second, phone) = codec.issue(first, "手机", 2000L)
        val revoked = codec.revoke(second, laptop.id)
        assertNull(codec.findByToken(revoked, laptop.token))
        assertNotNull(codec.findByToken(revoked, phone.token))
    }

    @Test
    fun tokensAndIdsAreUnpredictable() {
        val (list, one) = codec.issue(emptyList(), "a", 1L)
        val (_, two) = codec.issue(list, "b", 2L)
        assertNotEquals(one.token, two.token)
        assertNotEquals(one.id, two.id)
        assertTrue(one.token.length >= 32)
    }

    @Test
    fun capacityEvictsRevokedEntriesFirst() {
        val capped = NodeCredentials(capacity = 2)
        var list = emptyList<NodeCredentials.Credential>()
        var last: NodeCredentials.Credential? = null
        repeat(3) { index ->
            val (next, credential) = capped.issue(list, "设备$index", index.toLong())
            list = next
            last = credential
        }
        assertEquals(2, list.size)
        assertNotNull(capped.findByToken(list, last!!.token))
    }

    @Test
    fun pairingCodeIsSixDigits() {
        repeat(20) {
            assertEquals(6, codec.pairingCode().length)
        }
    }

    @Test
    fun fingerprintIsStableAndReadable() {
        val first = codec.deviceFingerprint("device-1")
        assertEquals(first, codec.deviceFingerprint("device-1"))
        assertNotEquals(first, codec.deviceFingerprint("device-2"))
        assertEquals(8, first.split(":").size)
    }
}