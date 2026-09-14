/*
 *  Description: Kotlin port of ccproxy_engine.py's SseUsage — scrape a /v1/messages turn's usage/model
 *               out of an SSE response INCREMENTALLY as bytes stream through, never holding the whole
 *               body. Anthropic puts input/cache tokens on message_start and the output count on
 *               message_delta, so both ends must be observed; a head-capped copy would miss the tail
 *               on a long turn. Only a single partial line is retained, so memory stays O(one SSE
 *               line). This class is the single most important piece to get right per the task brief:
 *               a prior retrofit-after-the-fact of streaming metering silently dropped usage for 26h
 *               in the Python engine (see memory ccproxy-metering-compressed-regression) — here it is
 *               streaming-first from day one.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class SseUsage(private val mapper: ObjectMapper) {
    private var buf = ByteArrayOutputStream()
    var model: String? = null
        private set

    var inputTokens: Long = 0
        private set

    var outputTokens: Long = 0
        private set

    var cacheReadTokens: Long = 0
        private set

    var cacheWriteTokens: Long = 0
        private set

    fun feed(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        buf.write(chunk)
        val all = buf.toByteArray()
        var start = 0
        val lines = mutableListOf<ByteArray>()
        for (i in all.indices) {
            if (all[i] == '\n'.code.toByte()) {
                lines.add(all.copyOfRange(start, i))
                start = i + 1
            }
        }
        buf = ByteArrayOutputStream()
        if (start < all.size) buf.write(all, start, all.size - start)
        for (ln in lines) line(ln)
        // no newline in 1 MiB: not a usage line, don't hoard it.
        if (buf.size() > (1 shl 20)) buf = ByteArrayOutputStream()
    }

    fun close() {
        if (buf.size() > 0) {
            line(buf.toByteArray())
            buf = ByteArrayOutputStream()
        }
    }

    private fun line(raw: ByteArray) {
        val s = String(raw, StandardCharsets.UTF_8).trim()
        if (!s.startsWith("data:")) return
        val payload = s.substring(5).trim()
        val ev =
            try {
                mapper.readTree(payload)
            } catch (e: Exception) {
                return
            } ?: return
        val msg = (ev.get("message") as? ObjectNode) ?: (ev as? ObjectNode) ?: return
        if (model == null) msg.get("model")?.takeIf { it.isTextual }?.let { model = it.asText() }
        val usage = (msg.get("usage") as? ObjectNode) ?: (ev.get("usage") as? ObjectNode)
        if (usage != null) {
            usage.get("input_tokens")?.takeIf { it.isNumber }?.let { inputTokens = it.asLong() }
            usage.get("output_tokens")?.takeIf { it.isNumber }?.let { outputTokens = it.asLong() }
            usage
                .get("cache_read_input_tokens")
                ?.takeIf { it.isNumber }
                ?.let {
                    cacheReadTokens = it.asLong()
                }
            usage
                .get("cache_creation_input_tokens")
                ?.takeIf { it.isNumber }
                ?.let {
                    cacheWriteTokens = it.asLong()
                }
        }
    }

    fun hasUsage(): Boolean = model != null || inputTokens != 0L || outputTokens != 0L
}
