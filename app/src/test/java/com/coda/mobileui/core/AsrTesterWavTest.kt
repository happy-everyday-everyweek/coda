package com.coda.mobileui.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * WAV 数据段解析的回归测试。
 *
 * 对应修复：块长度原来按有符号 Int 拼装，超过 2GB 会变成负数，遍历指针往回跳，
 * 畸形文件可以让解析死循环或越界崩溃。
 */
class AsrTesterWavTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun le32(value: Long): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(le32(0))
        out.write("WAVE".toByteArray())
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun chunk(
        id: String,
        size: Long,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray())
        out.write(le32(size))
        out.write(payload)
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray): ByteArray {
        val f = temp.newFile()
        f.writeBytes(bytes)
        return AsrTester.readWavPcm(f)
    }

    @Test
    fun readsDataChunkPayload() {
        val payload = byteArrayOf(1, 2, 3, 4)
        assertEquals(4, parse(riff(chunk("data", 4, payload))).size)
        assertArrayEquals(payload, parse(riff(chunk("data", 4, payload))))
    }

    @Test
    fun skipsUnknownChunkBeforeData() {
        val payload = byteArrayOf(9, 8)
        val bytes = riff(chunk("LIST", 4, byteArrayOf(0, 0, 0, 0)), chunk("data", 2, payload))
        assertArrayEquals(payload, parse(bytes))
    }

    @Test
    fun hugeDataSizeIsClampedToFileLength() {
        val payload = byteArrayOf(7, 7, 7)
        // size 声明为 0xFFFFFFFF：按有符号解析会变成 -1，旧实现会越界或死循环。
        assertArrayEquals(payload, parse(riff(chunk("data", 0xFFFFFFFFL, payload))))
    }

    @Test
    fun nonAdvancingChunkStopsParsing() {
        // LIST 长度 0 且没有其它块：旧实现 pos 不前进，会在这里无限循环。
        assertEquals(0, parse(riff(chunk("LIST", 0, ByteArray(0)))).size)
    }

    @Test
    fun returnsRawBytesWhenNotRiff() {
        val raw = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(raw, parse(raw))
    }

    @Test
    fun emptyFileReadsAsEmpty() {
        assertEquals(0, parse(ByteArray(0)).size)
    }
}