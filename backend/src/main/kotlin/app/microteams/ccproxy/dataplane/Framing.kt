/*
 *  Description: HTTP/1.1 framing helpers — the Kotlin port of ccproxy_engine.py's recv_line/
 *               recv_exact/parse_headers/read_body/relay_body/send_head. Operate on plain
 *               InputStream/OutputStream so the same code works for a raw Socket leg or an SSLSocket
 *               leg. relayBody is the streaming-relay core (item 7 of the task): bodies are always
 *               moved block-by-block, NEVER buffered whole, preserving chunked / Content-Length / EOF
 *               framing — this is what keeps a large transfer from stalling on O(n^2) concatenation or
 *               blowing memory (see memory ccproxy-engine-fullbuffer-largefile).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

const val LF: Int = '\n'.code
const val CR: Int = '\r'.code

/** Read one newline-terminated line (including the terminator), or null on EOF before any bytes. */
fun recvLine(src: InputStream): ByteArray? {
    val out = ByteArrayOutputStream(128)
    while (true) {
        val b = src.read()
        if (b < 0) return if (out.size() == 0) null else out.toByteArray()
        out.write(b)
        if (b == LF) return out.toByteArray()
    }
}

/** Read exactly n bytes, or null on EOF before n bytes arrived. */
fun recvExact(src: InputStream, n: Int): ByteArray? {
    if (n <= 0) return ByteArray(0)
    val out = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = src.read(out, got, n - got)
        if (r < 0) return null
        got += r
    }
    return out
}

/**
 * Case-sensitive-key, insertion-ordered header map (values as received; callers lowercase-compare
 * keys as needed, matching Python's use of a plain dict keyed by the header name as sent).
 */
fun parseHeaders(src: InputStream): LinkedHashMap<String, String>? {
    val h = LinkedHashMap<String, String>()
    while (true) {
        val lineBytes = recvLine(src) ?: return null
        val line = String(lineBytes, StandardCharsets.ISO_8859_1).trim()
        if (line.isEmpty()) break
        val i = line.indexOf(':')
        if (i > 0) {
            h[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
    }
    return h
}

fun headerIgnoreCase(headers: Map<String, String>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/**
 * Fully read a (small) body per its framing — only used on the tiny, fully-buffered oauth/token
 * path; everything else uses [relayBody].
 */
fun readBodyFully(src: InputStream, headers: Map<String, String>): ByteArray? {
    val te = headerIgnoreCase(headers, "Transfer-Encoding")?.lowercase() ?: ""
    if ("chunked" in te) {
        val out = ByteArrayOutputStream()
        while (true) {
            val lineBytes = recvLine(src) ?: return null
            val line = String(lineBytes, StandardCharsets.ISO_8859_1).trim()
            if (line.isEmpty()) continue
            val size =
                try {
                    line.substringBefore(';').trim().toInt(16)
                } catch (e: NumberFormatException) {
                    return out.toByteArray()
                }
            if (size == 0) {
                recvLine(src)
                break
            }
            val chunk = recvExact(src, size) ?: return null
            out.write(chunk)
            recvLine(src)
        }
        return out.toByteArray()
    }
    val cl = headerIgnoreCase(headers, "Content-Length")?.toIntOrNull() ?: 0
    return if (cl > 0) recvExact(src, cl) else ByteArray(0)
}

fun sendHead(dst: OutputStream, firstLine: String, headers: Map<String, String>) {
    val sb = StringBuilder()
    sb.append(firstLine)
    if (!firstLine.endsWith("\r\n")) sb.append("\r\n")
    for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
    sb.append("\r\n")
    dst.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
    dst.flush()
}

/**
 * Result of a streamed relay: teeBytes (the full decoded body, byte-exact, or null if tee==false),
 * eofUsed (body ran to connection close — caller must not keep-alive).
 */
data class RelayResult(val teeBytes: ByteArray?, val eofUsed: Boolean)

/**
 * Relay a message body src->dst preserving its framing (chunked / Content-Length / EOF-delimited),
 * never holding more than [streamBlock] bytes in flight. [tap], if given, is called with every
 * decoded block for unbounded incremental metering (SseUsage) — memory there stays O(1), unlike the
 * tee (which holds the whole decoded body, for callers that need a byte-exact copy, e.g. the dump).
 * Direct Kotlin analogue of ccproxy_engine.py's relay_body.
 */
fun relayBody(
    src: InputStream,
    dst: OutputStream,
    headers: Map<String, String>,
    streamBlock: Int,
    tee: Boolean = false,
    allowEof: Boolean = false,
    tap: ((ByteArray) -> Unit)? = null,
): RelayResult {
    val teeBuf = if (tee) ByteArrayOutputStream() else null

    fun teeAdd(data: ByteArray, len: Int) {
        teeBuf?.write(data, 0, len)
    }

    fun result(eof: Boolean) = RelayResult(teeBuf?.toByteArray(), eof)

    val te = headerIgnoreCase(headers, "Transfer-Encoding")?.lowercase() ?: ""
    if ("chunked" in te) {
        while (true) {
            val lineBytes = recvLine(src) ?: return result(false)
            dst.write(lineBytes)
            val lineStr = String(lineBytes, StandardCharsets.ISO_8859_1).trim()
            val size =
                try {
                    lineStr.substringBefore(';').trim().toInt(16)
                } catch (e: NumberFormatException) {
                    return result(false)
                }
            if (size == 0) {
                while (true) {
                    val t = recvLine(src) ?: break
                    dst.write(t)
                    if (
                        t.contentEquals("\r\n".toByteArray()) || t.contentEquals("\n".toByteArray())
                    )
                        break
                }
                dst.flush()
                return result(false)
            }
            var remaining = size
            while (remaining > 0) {
                val blk = ByteArray(minOf(streamBlock, remaining))
                val r = src.read(blk)
                if (r < 0) return result(false)
                val actual = blk.copyOf(r)
                dst.write(actual)
                teeAdd(actual, actual.size)
                tap?.invoke(actual)
                remaining -= r
            }
            dst.flush()
            val trail = recvExact(src, 2)
            dst.write(trail ?: "\r\n".toByteArray())
        }
    }
    val cl = headerIgnoreCase(headers, "Content-Length")
    if (cl != null) {
        var remaining =
            try {
                cl.toInt()
            } catch (e: NumberFormatException) {
                0
            }
        while (remaining > 0) {
            val blk = ByteArray(minOf(streamBlock, remaining))
            val r = src.read(blk)
            if (r < 0) break
            val actual = blk.copyOf(r)
            dst.write(actual)
            teeAdd(actual, actual.size)
            remaining -= r
        }
        dst.flush()
        return result(false)
    }
    if (allowEof) {
        while (true) {
            val blk = ByteArray(streamBlock)
            val r =
                try {
                    src.read(blk)
                } catch (e: IOException) {
                    -1
                }
            if (r < 0) break
            val actual = blk.copyOf(r)
            dst.write(actual)
            teeAdd(actual, actual.size)
            tap?.invoke(actual)
        }
        dst.flush()
        return result(true)
    }
    return result(false)
}
