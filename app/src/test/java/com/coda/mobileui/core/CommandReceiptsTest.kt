package com.coda.mobileui.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandReceiptsTest {
    @Test
    fun repeatsReturnSameReceipt() {
        val receipts = CommandReceipts()
        receipts.put("c1", CommandReceipts.Entry(result = JSONObject().put("ok", true)))
        assertEquals(true, receipts.get("c1")?.result?.getBoolean("ok"))
    }

    @Test
    fun blankCommandIdIsIgnored() {
        val receipts = CommandReceipts()
        receipts.put("", CommandReceipts.Entry(result = JSONObject()))
        assertEquals(0, receipts.size())
    }

    @Test
    fun leastRecentlyUsedEntryIsEvicted() {
        val receipts = CommandReceipts(capacity = 2)
        receipts.put("a", CommandReceipts.Entry(result = JSONObject()))
        receipts.put("b", CommandReceipts.Entry(result = JSONObject()))
        receipts.get("a")
        receipts.put("c", CommandReceipts.Entry(result = JSONObject()))
        assertNull(receipts.get("b"))
        assertTrue(receipts.get("a") != null)
        assertTrue(receipts.get("c") != null)
    }

    @Test
    fun errorReceiptKeepsCode() {
        val receipts = CommandReceipts()
        receipts.put("e1", CommandReceipts.Entry(errorCode = NodeBridgeProtocol.ERR_INTERNAL, errorMessage = "boom"))
        assertEquals(NodeBridgeProtocol.ERR_INTERNAL, receipts.get("e1")?.errorCode ?: 0)
        assertEquals("boom", receipts.get("e1")?.errorMessage)
    }
}