package app.microteams.ccproxy.dataplane

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class SseUsageTest {
    private val mapper = ObjectMapper()

    private val messageStart =
        """data: {"type":"message_start","message":{"model":"claude-sonnet-5","usage":{"input_tokens":12,"cache_read_input_tokens":3,"cache_creation_input_tokens":1}}}""" +
            "\n\n"
    private val messageDelta =
        """data: {"type":"message_delta","usage":{"output_tokens":34}}""" + "\n\n"

    @Test
    fun `parses usage when fed as one whole chunk`() {
        val u = SseUsage(mapper)
        u.feed((messageStart + messageDelta).toByteArray())
        u.close()
        assertEquals("claude-sonnet-5", u.model)
        assertEquals(12, u.inputTokens)
        assertEquals(34, u.outputTokens)
        assertEquals(3, u.cacheReadTokens)
        assertEquals(1, u.cacheWriteTokens)
    }

    @Test
    fun `parses usage when split across arbitrary byte boundaries, including mid-line`() {
        val whole = (messageStart + messageDelta).toByteArray()
        val u = SseUsage(mapper)
        // Feed one byte at a time — the worst case for a line-buffering scraper.
        for (b in whole) u.feed(byteArrayOf(b))
        u.close()
        assertEquals("claude-sonnet-5", u.model)
        assertEquals(12, u.inputTokens)
        assertEquals(34, u.outputTokens)
    }

    @Test
    fun `parses usage when split exactly across a JSON line boundary`() {
        val line = messageStart.trimEnd('\n')
        val mid = line.length / 2
        val u = SseUsage(mapper)
        u.feed(line.substring(0, mid).toByteArray())
        u.feed(line.substring(mid).toByteArray())
        u.feed("\n\n".toByteArray())
        u.feed(messageDelta.toByteArray())
        u.close()
        assertEquals("claude-sonnet-5", u.model)
        assertEquals(12, u.inputTokens)
        assertEquals(34, u.outputTokens)
    }

    @Test
    fun `a trailing partial line with no newline is still picked up by close()`() {
        val u = SseUsage(mapper)
        u.feed(messageStart.toByteArray())
        // message_delta fed WITHOUT a trailing newline — only close() should flush it.
        u.feed(messageDelta.trimEnd('\n').toByteArray())
        assertEquals(0, u.outputTokens) // not yet flushed
        u.close()
        assertEquals(34, u.outputTokens)
    }

    @Test
    fun `non-data lines and blank lines are ignored`() {
        val u = SseUsage(mapper)
        u.feed("event: message_start\n\n".toByteArray())
        u.feed(messageStart.toByteArray())
        u.feed(messageDelta.toByteArray())
        u.close()
        assertEquals(12, u.inputTokens)
    }

    @Test
    fun `hasUsage is false when nothing was observed`() {
        val u = SseUsage(mapper)
        u.feed("data: {\"type\":\"ping\"}\n\n".toByteArray())
        u.close()
        assertEquals(false, u.hasUsage())
    }
}
