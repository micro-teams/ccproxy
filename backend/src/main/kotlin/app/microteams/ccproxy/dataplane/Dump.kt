/*
 *  Description: Optional traffic dump — the Kotlin dataplane's replacement for the old Python
 *               engine's CCPROXY_DUMP_DIR (dump_exchange). NDJSON instead of one-.http-file-per-
 *               exchange: one HAR ("HTTP Archive", the standard defined by
 *               http://www.softwareishard.com/blog/har-12-spec/ — the format itself IS JSON, not a
 *               convention layered on top) entry-shaped JSON object per line, appended to a rolling
 *               per-machine file (app_data/dumps/<machine>/<yyyy-MM-dd>.ndjson).
 *
 *               Chosen for three properties (2026-09-15 design discussion): standard (HAR is a
 *               widely-understood wire format, not a private one), comprehensive (every header is
 *               recorded, unclipped — including the ones that mattered for past forensics, e.g.
 *               anthropic-organization-id / anthropic-ratelimit-unified-*), and compressible (far
 *               fewer, larger files than the old per-request-file layout, with near-identical JSON
 *               structure repeated across every line — better input for the xz large-dictionary
 *               archival pipeline documented in cycle-20260803-20260910.md §9). It also removes the
 *               old format's landmine entirely: a request body's JSON string field can never be
 *               confused with an HTTP framing boundary, unlike the old scheme where a tool_result
 *               literally containing the text "HTTP/1.1" once corrupted a naive parser.
 *
 *               Records the CLIENT's view (fake credential, pre-swap) — real tokens are never
 *               written to disk, matching the old engine's invariant. Bodies are captured whole,
 *               byte-exact, no cap (2026-09-15: nictheboy — every byte of traffic through ccproxy
 *               must be archived unmodified) via relayBody's tee, coexisting with the O(1) SseUsage
 *               tap on the same relayBody call. Best-effort and always off the hot path (a daemon
 *               thread per write): a slow or failing disk must never affect what the client receives.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

data class DumpHeader(val name: String, val value: String)

data class DumpContent(val size: Int, val text: String)

data class DumpRequest(
    val method: String,
    val url: String,
    val httpVersion: String,
    val headers: List<DumpHeader>,
    val postData: DumpContent?,
)

data class DumpResponse(
    val status: Int,
    val statusText: String,
    val httpVersion: String,
    val headers: List<DumpHeader>,
    val content: DumpContent?,
)

data class DumpEntry(
    val startedDateTime: String,
    val machine: String,
    val request: DumpRequest,
    val response: DumpResponse,
)

class Dump(private val dumpDir: String, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(Dump::class.java)
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    // One lock per rolling file path, so concurrent writers to the SAME file (same machine, same
    // day) never interleave lines, while different machines/days write fully in parallel.
    private val fileLocks = ConcurrentHashMap<String, Any>()

    val enabled: Boolean
        get() = dumpDir.isNotBlank()

    /** Fire-and-forget: runs the actual write on a daemon thread, never blocking the caller. */
    fun writeAsync(
        machine: String,
        method: String,
        path: String,
        host: String,
        reqHeaders: Map<String, String>,
        reqBody: ByteArray?,
        statusLine: String,
        respHeaders: Map<String, String>,
        respBody: ByteArray?,
    ) {
        if (!enabled) return
        val t = Thread {
            try {
                write(
                    machine,
                    method,
                    path,
                    host,
                    reqHeaders,
                    reqBody,
                    statusLine,
                    respHeaders,
                    respBody,
                )
            } catch (e: Exception) {
                log.warn("dump write failed for $machine: ${e.message?.take(80)}")
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun write(
        machine: String,
        method: String,
        path: String,
        host: String,
        reqHeaders: Map<String, String>,
        reqBody: ByteArray?,
        statusLine: String,
        respHeaders: Map<String, String>,
        respBody: ByteArray?,
    ) {
        val now = Instant.now()
        val (status, statusText) = parseStatusLine(statusLine)
        val entry =
            DumpEntry(
                startedDateTime = now.toString(),
                machine = machine,
                request =
                    DumpRequest(
                        method = method,
                        url = "https://$host$path",
                        httpVersion = "HTTP/1.1",
                        headers = reqHeaders.map { (k, v) -> DumpHeader(k, v) },
                        postData = bodyToContent(reqBody),
                    ),
                response =
                    DumpResponse(
                        status = status,
                        statusText = statusText,
                        httpVersion = "HTTP/1.1",
                        headers = respHeaders.map { (k, v) -> DumpHeader(k, v) },
                        content = bodyToContent(respBody),
                    ),
            )
        val line = mapper.writeValueAsString(entry) + "\n"
        val dir = File(dumpDir, safeSegment(machine))
        dir.mkdirs()
        val file = File(dir, "${dayFormatter.format(now)}.ndjson")
        val lock = fileLocks.computeIfAbsent(file.absolutePath) { Any() }
        synchronized(lock) {
            Files.write(
                file.toPath(),
                line.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun bodyToContent(body: ByteArray?): DumpContent? {
        if (body == null) return null
        return DumpContent(size = body.size, text = String(body, StandardCharsets.UTF_8))
    }

    private fun parseStatusLine(statusLine: String): Pair<Int, String> {
        // Accepts either "HTTP/1.1 200 OK" (raw upstream status line) or "200 OK" (already
        // stripped, as forward()'s buffered oauth/token path hands us) — the code is whichever
        // token parses as an integer.
        val parts = statusLine.trim().split(Regex("\\s+"))
        val codeIdx = parts.indexOfFirst { it.toIntOrNull() != null }
        if (codeIdx < 0) return 0 to statusLine.trim()
        val code = parts[codeIdx].toInt()
        val text = parts.drop(codeIdx + 1).joinToString(" ")
        return code to text
    }

    private fun safeSegment(s: String): String =
        s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .ifBlank { "unknown" }
}
