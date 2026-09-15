package app.microteams.ccproxy.dataplane

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FramingTest {
    @Test
    fun `recvLine reads a CRLF line exactly`() {
        val src = ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        assertEquals("GET / HTTP/1.1\r\n", String(recvLine(src)!!))
        assertEquals("Host: x\r\n", String(recvLine(src)!!))
        assertEquals("\r\n", String(recvLine(src)!!))
    }

    @Test
    fun `recvExact reads exactly n bytes and returns null on premature EOF`() {
        val src = ByteArrayInputStream("hello".toByteArray())
        assertEquals("hello", String(recvExact(src, 5)!!))
        val short = ByteArrayInputStream("ab".toByteArray())
        assertNull(recvExact(short, 5))
    }

    @Test
    fun `parseHeaders builds a map and stops at the blank line`() {
        val src =
            ByteArrayInputStream(
                "Content-Type: application/json\r\nX-Foo: bar\r\n\r\nBODY".toByteArray()
            )
        val h = parseHeaders(src)!!
        assertEquals("application/json", h["Content-Type"])
        assertEquals("bar", h["X-Foo"])
        // stream positioned right after the blank line
        assertEquals("BODY", src.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun `relayBody relays a Content-Length body byte-exact across a small stream block`() {
        val payload = "0123456789".repeat(1000) // 10000 bytes
        val src = ByteArrayInputStream(payload.toByteArray())
        val dst = ByteArrayOutputStream()
        val headers = mapOf("Content-Length" to payload.length.toString())
        val result = relayBody(src, dst, headers, streamBlock = 37, tee = false, allowEof = false)
        assertEquals(payload, dst.toByteArray().toString(Charsets.UTF_8))
        assertEquals(false, result.eofUsed)
        assertNull(result.teeBytes)
    }

    @Test
    fun `relayBody relays a chunked body byte-exact, decoding chunk framing for the tap`() {
        val chunked = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        val src = ByteArrayInputStream(chunked.toByteArray())
        val dst = ByteArrayOutputStream()
        val headers = mapOf("Transfer-Encoding" to "chunked")
        val tapped = ByteArrayOutputStream()
        val result =
            relayBody(
                src,
                dst,
                headers,
                streamBlock = 3,
                tee = true,
                allowEof = false,
                tap = { tapped.write(it) },
            )
        // the wire framing is relayed verbatim...
        assertEquals(chunked, dst.toByteArray().toString(Charsets.UTF_8))
        // ...while the tap/tee see only the decoded payload, no chunk-size lines.
        assertEquals("hello world", tapped.toByteArray().toString(Charsets.UTF_8))
        assertEquals("hello world", result.teeBytes!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `relayBody with no framing and allowEof relays until the source closes`() {
        val payload = "stream to EOF"
        val src = ByteArrayInputStream(payload.toByteArray())
        val dst = ByteArrayOutputStream()
        val result = relayBody(src, dst, emptyMap(), streamBlock = 4, tee = false, allowEof = true)
        assertEquals(payload, dst.toByteArray().toString(Charsets.UTF_8))
        assertTrue(result.eofUsed)
    }

    @Test
    fun `relayBody tee captures the full body, byte-exact, uncapped`() {
        val payload = "x".repeat(500)
        val src = ByteArrayInputStream(payload.toByteArray())
        val dst = ByteArrayOutputStream()
        val headers = mapOf("Content-Length" to "500")
        val result = relayBody(src, dst, headers, streamBlock = 64, tee = true, allowEof = false)
        assertEquals(500, dst.size())
        assertEquals(500, result.teeBytes!!.size)
    }
}
