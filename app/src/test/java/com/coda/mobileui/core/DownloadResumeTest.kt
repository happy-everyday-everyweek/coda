package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续传的判定逻辑（#730）。
 *
 * 这些判定以前不存在：每次下载都从 0 开始，异常就删临时文件。抽成纯函数后，
 * 「服务端到底支不支持 Range」「远端变了要不要作废」「半截 JSON 怎么办」
 * 都能在 JVM 里钉住，不必真机断网复现。
 */
class DownloadResumeTest {
    @Test
    fun `206 且 Content-Range 起点吻合才算续传成功`() {
        assertEquals(
            ResumeProbe.Kind.RESUMED,
            ResumeProbe.ofResponse(code = 206, requestedFrom = 1000, contentRange = "bytes 1000-1999/2000"),
        )
    }

    @Test
    fun `206 但起点不是请求的位置时判不可信`() {
        // 有些代理会无视 Range 起点返回别的区间，照拼会拼出损坏文件。
        assertEquals(
            ResumeProbe.Kind.UNTRUSTED,
            ResumeProbe.ofResponse(code = 206, requestedFrom = 1000, contentRange = "bytes 0-1999/2000"),
        )
    }

    @Test
    fun `206 缺 Content-Range 判不可信`() {
        assertEquals(
            ResumeProbe.Kind.UNTRUSTED,
            ResumeProbe.ofResponse(code = 206, requestedFrom = 1000, contentRange = null),
        )
        assertEquals(
            ResumeProbe.Kind.UNTRUSTED,
            ResumeProbe.ofResponse(code = 206, requestedFrom = 1000, contentRange = "garbage"),
        )
    }

    @Test
    fun `请求续传却回 200 说明服务端不支持，应全量重下`() {
        assertEquals(
            ResumeProbe.Kind.NOT_SUPPORTED,
            ResumeProbe.ofResponse(code = 200, requestedFrom = 1000, contentRange = null),
        )
    }

    @Test
    fun `416 说明断点超出远端范围，作废重下`() {
        assertEquals(
            ResumeProbe.Kind.INVALID_RANGE,
            ResumeProbe.ofResponse(code = 416, requestedFrom = 5000, contentRange = null),
        )
    }

    @Test
    fun `其它状态码交给 HTTP 分类，不在这里判续传`() {
        assertEquals(
            ResumeProbe.Kind.HTTP_FAILURE,
            ResumeProbe.ofResponse(code = 403, requestedFrom = 100, contentRange = null),
        )
        assertEquals(
            ResumeProbe.Kind.HTTP_FAILURE,
            ResumeProbe.ofResponse(code = 502, requestedFrom = 100, contentRange = null),
        )
    }

    @Test
    fun `Accept-Ranges 明确 bytes 才算支持`() {
        assertTrue(ResumeProbe.acceptsRanges("bytes"))
        assertTrue(ResumeProbe.acceptsRanges("BYTES"))
        assertFalse(ResumeProbe.acceptsRanges("none"))
        assertFalse(ResumeProbe.acceptsRanges(null))
        assertFalse(ResumeProbe.acceptsRanges(""))
    }

    @Test
    fun `Content-Range 解析出起点与总长`() {
        val r = ResumeProbe.parseContentRange("bytes 1000-1999/2000")
        assertEquals(1000L, r?.from)
        assertEquals(2000L, r?.total)
    }

    @Test
    fun `总长未知用星号表示时 total 为 0`() {
        val r = ResumeProbe.parseContentRange("bytes 0-99/*")
        assertEquals(0L, r?.from)
        assertEquals(0L, r?.total)
    }

    @Test
    fun `非法 Content-Range 一律解析失败`() {
        assertNull(ResumeProbe.parseContentRange(null))
        assertNull(ResumeProbe.parseContentRange(""))
        assertNull(ResumeProbe.parseContentRange("items 0-9/10"))
        assertNull(ResumeProbe.parseContentRange("bytes abc-9/10"))
        assertNull(ResumeProbe.parseContentRange("bytes 0-9"))
    }

    @Test
    fun `URL 与已有字节都吻合才允许续传`() {
        val st = ResumeState(url = "https://a/f", done = 500, total = 1000, etag = "\"e1\"", lastModified = null)
        assertEquals(
            ResumeState.Verdict.RESUME,
            ResumeState.check(st, url = "https://a/f", fileBytes = 500, etag = "\"e1\"", lastModified = null),
        )
    }

    @Test
    fun `ETag 变了作废重下`() {
        val st = ResumeState(url = "https://a/f", done = 500, total = 1000, etag = "\"e1\"", lastModified = null)
        assertEquals(
            ResumeState.Verdict.DISCARD_REMOTE_CHANGED,
            ResumeState.check(st, url = "https://a/f", fileBytes = 500, etag = "\"e2\"", lastModified = null),
        )
    }

    @Test
    fun `Last-Modified 变了也作废`() {
        val st = ResumeState(url = "https://a/f", done = 500, total = 1000, etag = null, lastModified = "Mon, 01 Jan 2024")
        assertEquals(
            ResumeState.Verdict.DISCARD_REMOTE_CHANGED,
            ResumeState.check(st, url = "https://a/f", fileBytes = 500, etag = null, lastModified = "Tue, 02 Jan 2024"),
        )
    }

    @Test
    fun `两侧都没有校验标识时不敢续传`() {
        // 无法判断远端是否变过，拼接风险高于省下的流量。
        val st = ResumeState(url = "https://a/f", done = 500, total = 1000, etag = null, lastModified = null)
        assertEquals(
            ResumeState.Verdict.DISCARD_NO_VALIDATOR,
            ResumeState.check(st, url = "https://a/f", fileBytes = 500, etag = null, lastModified = null),
        )
    }

    @Test
    fun `URL 不同作废`() {
        val st = ResumeState(url = "https://a/f", done = 500, total = 1000, etag = "\"e1\"", lastModified = null)
        assertEquals(
            ResumeState.Verdict.DISCARD_URL,
            ResumeState.check(st, url = "https://b/f", fileBytes = 500, etag = "\"e1\"", lastModified = null),
        )
    }

    @Test
    fun `磁盘字节少于记录说明文件被截断，作废`() {
        val st = ResumeState(url = "https://a/f", done = 500, total = 1000, etag = "\"e1\"", lastModified = null)
        assertEquals(
            ResumeState.Verdict.DISCARD_FILE_MISMATCH,
            ResumeState.check(st, url = "https://a/f", fileBytes = 400, etag = "\"e1\"", lastModified = null),
        )
    }

    @Test
    fun `磁盘字节多于记录时以磁盘为准继续，不作废`() {
        // 上次写完字节但没来得及更新 sidecar 就被杀：多出来的字节是好的。
        val st = ResumeState(url = "https://a/f", done = 500, total = 1000, etag = "\"e1\"", lastModified = null)
        assertEquals(
            ResumeState.Verdict.RESUME,
            ResumeState.check(st, url = "https://a/f", fileBytes = 700, etag = "\"e1\"", lastModified = null),
        )
    }

    @Test
    fun `已下满就不再续传`() {
        val st = ResumeState(url = "https://a/f", done = 1000, total = 1000, etag = "\"e1\"", lastModified = null)
        assertEquals(
            ResumeState.Verdict.ALREADY_COMPLETE,
            ResumeState.check(st, url = "https://a/f", fileBytes = 1000, etag = "\"e1\"", lastModified = null),
        )
    }

    @Test
    fun `记录为 0 字节等于没有断点`() {
        val st = ResumeState(url = "https://a/f", done = 0, total = 1000, etag = "\"e1\"", lastModified = null)
        assertEquals(
            ResumeState.Verdict.DISCARD_EMPTY,
            ResumeState.check(st, url = "https://a/f", fileBytes = 0, etag = "\"e1\"", lastModified = null),
        )
    }

    @Test
    fun `sidecar 编解码往返一致`() {
        val st = ResumeState(url = "https://a/f", done = 500, total = 1000, etag = "\"e1\"", lastModified = "Mon")
        val back = ResumeState.decode(st.encode())
        assertEquals(st, back)
    }

    @Test
    fun `半截 JSON 与非法内容一律解析失败而不是抛异常`() {
        assertNull(ResumeState.decode(""))
        assertNull(ResumeState.decode("{\"url\":"))
        assertNull(ResumeState.decode("not json"))
        assertNull(ResumeState.decode("{}"))
        assertNull(ResumeState.decode("{\"url\":\"https://a/f\"}"))
    }

    @Test
    fun `负数字节数视为非法记录`() {
        assertNull(ResumeState.decode("""{"url":"https://a/f","done":-1,"total":10}"""))
    }

    @Test
    fun `确定性临时名不含纳秒，重启后能定位`() {
        val a = ResumeState.partNameFor("update.apk")
        val b = ResumeState.partNameFor("update.apk")
        assertEquals(a, b)
        assertTrue(a.startsWith("update.apk."))
        assertTrue(a.endsWith(".part"))
        assertFalse(a == "update.apk.part")
    }

    @Test
    fun `不同目标文件的临时名不冲突`() {
        assertFalse(ResumeState.partNameFor("a.apk") == ResumeState.partNameFor("b.apk"))
    }
}