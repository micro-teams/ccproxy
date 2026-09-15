/*
 *  Description: Kotlin port of ccproxy_engine.py's handle_mitm / forward / forward_streaming — the
 *               core per-request loop once a client's CONNECT has been TLS-terminated. Small
 *               oauth/token exchanges go through the fully-buffered swap/absorb path (forward);
 *               everything else, especially /v1/messages, streams both bodies (forwardStreaming),
 *               with the model gate applied before any upstream connection and SseUsage metering
 *               applied incrementally on the way through.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import org.slf4j.LoggerFactory

class MitmHandler(
    private val config: app.microteams.ccproxy.common.config.CCProxyConfig.Dataplane,
    private val mapper: ObjectMapper,
    private val reporting: DataplaneReporting,
    private val controlService: DataplaneControlService,
) {
    private val log = LoggerFactory.getLogger(MitmHandler::class.java)
    private val dump = Dump(config.dumpDir, mapper)

    fun isOauthTokenPath(path: String): Boolean = "oauth/token" in path.lowercase()

    /** Per-connection request loop over a TLS-terminated client socket. */
    fun handleMitm(clientTls: SSLSocket, host: String, port: Int, sess: Session, user: String) {
        var upstream: SSLSocket? = null

        fun getUpstream(): SSLSocket {
            var u = upstream
            if (u == null) {
                u =
                    UpstreamConnector.connectTls(
                        host,
                        port,
                        sess.accountProxy,
                        30_000,
                        config.upstreamTimeoutMs,
                    )
                upstream = u
            }
            return u
        }

        fun dropUpstream() {
            upstream?.let {
                try {
                    it.close()
                } catch (e: Exception) {}
            }
            upstream = null
        }

        try {
            val input = clientTls.inputStream
            val output = clientTls.outputStream
            while (true) {
                clientTls.soTimeout = config.clientIdleTimeoutMs
                val lineBytes = recvLine(input) ?: break
                val line = String(lineBytes, StandardCharsets.ISO_8859_1).trim()
                val parts = line.split(" ")
                if (parts.size < 3) break
                clientTls.soTimeout = config.clientActiveTimeoutMs
                val method = parts[0]
                val path = parts[1]
                val headers = parseHeaders(input) ?: break
                if (isOauthTokenPath(path)) {
                    val body = readBodyFully(input, headers)
                    val resp = forward(::getUpstream, method, path, headers, body, sess, user, host)
                    if (resp == null) break
                    output.write(resp)
                    output.flush()
                    if (headerIgnoreCase(headers, "Connection")?.lowercase() == "close") break
                } else {
                    val keepAlive =
                        forwardStreaming(
                            ::getUpstream,
                            ::dropUpstream,
                            method,
                            path,
                            headers,
                            clientTls,
                            sess,
                            user,
                            host,
                        )
                    if (!keepAlive) break
                }
            }
        } catch (e: Exception) {
            log.debug("mitm $host: ${e.message?.take(100)}")
        } finally {
            try {
                upstream?.close()
            } catch (e: Exception) {}
        }
    }

    /**
     * Small fully-buffered path for oauth/token exchanges: swap/absorb, then re-assemble a response
     * as raw bytes to write back to the client. Returns null on a dead upstream connection.
     */
    private fun forward(
        getUpstream: () -> SSLSocket,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        sess: Session,
        user: String,
        host: String,
    ): ByteArray? {
        val bodyText = body?.toString(StandardCharsets.UTF_8)
        val statusHeadersBody: Triple<String, MutableMap<String, String>, String>
        if (RefreshAbsorption.parseRefreshGrant(bodyText, sess, mapper)) {
            val resp =
                RefreshAbsorption.absorbRefresh(
                    sess,
                    config.refreshMarginSeconds,
                    config.refreshBackoffSeconds,
                    mapper,
                ) {
                    doUpstreamRefresh(getUpstream(), method, path, headers, bodyText, sess)
                }
            statusHeadersBody = Triple(resp.statusLine, resp.headers, resp.body)
        } else {
            val swappedBody = TokenSwap.swapRequestBody(bodyText, sess)
            val swappedHeaders = LinkedHashMap(headers)
            headerIgnoreCase(headers, "Authorization")?.let { auth ->
                val key = headers.entries.first { it.key.equals("Authorization", true) }.key
                swappedHeaders[key] = TokenSwap.swapAuthHeader(auth, sess) ?: auth
            }
            val upstream = getUpstream()
            sendRequestFully(
                upstream.outputStream,
                method,
                path,
                swappedHeaders,
                swappedBody?.toByteArray(StandardCharsets.UTF_8),
            )
            val resp = readResponseFully(upstream.inputStream) ?: return null
            val (status, rh, raw) = resp
            val outBody =
                TokenSwap.swapResponseBody(raw.toString(StandardCharsets.UTF_8), sess, mapper)
                    ?: raw.toString(StandardCharsets.UTF_8)
            // No usage report here: this path only ever handles oauth/token, which never matches
            // "/v1/messages" — mirrors Python's report_usage() no-op for this path.
            statusHeadersBody = Triple(status, rh, outBody)
        }
        val (status, rh, outBody) = statusHeadersBody
        val outBytes = outBody.toByteArray(StandardCharsets.UTF_8)
        rh["Content-Length"] = outBytes.size.toString()
        if (dump.enabled) {
            // Client's (fake-credential) view: the pre-swap headers/body this method was called
            // with, never the swapped/real ones — same invariant as the old engine's dump_exchange.
            dump.writeAsync(
                machine = user,
                method = method,
                path = path,
                host = host,
                reqHeaders = headers,
                reqBody = body,
                statusLine = status,
                respHeaders = rh,
                respBody = outBytes,
            )
        }
        val head = StringBuilder("HTTP/1.1 $status\r\n")
        for ((k, v) in rh) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        return head.toString().toByteArray(StandardCharsets.ISO_8859_1) + outBytes
    }

    private fun doUpstreamRefresh(
        upstream: SSLSocket,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String?,
        sess: Session,
    ): Triple<String, MutableMap<String, String>, String>? {
        val swappedHeaders = LinkedHashMap(headers)
        headerIgnoreCase(headers, "Authorization")?.let { auth ->
            val key = headers.entries.first { it.key.equals("Authorization", true) }.key
            swappedHeaders[key] = TokenSwap.swapAuthHeader(auth, sess) ?: auth
        }
        val swappedBody = TokenSwap.swapRequestBody(body, sess)
        sendRequestFully(
            upstream.outputStream,
            method,
            path,
            swappedHeaders,
            swappedBody?.toByteArray(StandardCharsets.UTF_8),
        )
        val resp = readResponseFully(upstream.inputStream) ?: return null
        val (status, rh, raw) = resp
        return Triple(status, rh, raw.toString(StandardCharsets.UTF_8))
    }

    private fun sendRequestFully(
        out: java.io.OutputStream,
        method: String,
        path: String,
        headers: MutableMap<String, String>,
        body: ByteArray?,
    ) {
        if (body != null) headers["Content-Length"] = body.size.toString()
        val sb = StringBuilder("$method $path HTTP/1.1\r\n")
        for ((k, v) in headers) {
            if (k.equals("content-length", true) && body == null) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (body != null) out.write(body)
        out.flush()
    }

    /**
     * Read one upstream response fully (small oauth/token bodies only): status line, headers, body.
     */
    private fun readResponseFully(
        input: java.io.InputStream
    ): Triple<String, LinkedHashMap<String, String>, ByteArray>? {
        val statusBytes = recvLine(input) ?: return null
        val status =
            String(statusBytes, StandardCharsets.ISO_8859_1)
                .trim()
                .removePrefix("HTTP/1.1 ")
                .removePrefix("HTTP/1.0 ")
        val rh = parseHeaders(input) ?: return null
        val body = readBodyFully(input, rh) ?: ByteArray(0)
        rh.remove("Transfer-Encoding")
        return Triple(status, rh, body)
    }

    /**
     * Relay one non-oauth exchange with both bodies STREAMED. Returns true to keep the connection
     * alive for the next request, false to close it.
     */
    private fun forwardStreaming(
        getUpstream: () -> SSLSocket,
        dropUpstream: () -> Unit,
        method: String,
        path: String,
        headers: Map<String, String>,
        clientTls: SSLSocket,
        sess: Session,
        user: String,
        host: String,
    ): Boolean {
        val isMessages = "/v1/messages" in path
        val workingHeaders = LinkedHashMap(headers)
        // Client's (fake-credential, pre-swap) view for the optional dump — captured before
        // Authorization gets swapped to the real token below.
        val dumpReqHeaders = if (dump.enabled) LinkedHashMap(headers) else null

        // Model gate (item 10): peek the body prefix BEFORE opening any upstream connection.
        var bodyPrefix = ByteArray(0)
        var bodyPrefixRemaining: Int? = null
        val blocked = controlService.getBlockedModelFamilies()
        if (
            isMessages &&
                blocked.isNotEmpty() &&
                method == "POST" &&
                headerIgnoreCase(workingHeaders, "Transfer-Encoding")?.lowercase() != "chunked"
        ) {
            val cl = headerIgnoreCase(workingHeaders, "Content-Length")?.toIntOrNull()
            if (cl != null && cl > 0) {
                val peekLen = minOf(cl, config.modelSniffCap)
                val peeked = recvExact(clientTls.inputStream, peekLen)
                if (peeked != null) {
                    bodyPrefix = peeked
                    bodyPrefixRemaining = cl - peeked.size
                    val model = ModelGate.extractModel(peeked)
                    val reject = ModelGate.reject(model, blocked)
                    if (reject != null) {
                        sendError(clientTls, 400, reject, "invalid_request_error")
                        return false
                    }
                } else {
                    bodyPrefixRemaining = 0
                }
            }
        }

        if (isMessages) {
            // Force an uncompressed upstream response so the streaming meter can parse the SSE.
            val toRemove = workingHeaders.keys.filter { it.equals("Accept-Encoding", true) }
            toRemove.forEach { workingHeaders.remove(it) }
            workingHeaders["Accept-Encoding"] = "identity"
        }
        headerIgnoreCase(workingHeaders, "Authorization")?.let { auth ->
            val key = workingHeaders.entries.first { it.key.equals("Authorization", true) }.key
            workingHeaders[key] = TokenSwap.swapAuthHeader(auth, sess) ?: auth
        }

        var up: SSLSocket? = null
        for (attempt in 0 until config.upstreamConnectAttempts) {
            try {
                up = getUpstream()
                sendHead(up.outputStream, "$method $path HTTP/1.1", workingHeaders)
                break
            } catch (e: Exception) {
                dropUpstream()
                up = null
                log.debug(
                    "$user: upstream connect/send attempt ${attempt + 1}/${config.upstreamConnectAttempts} failed: ${e.message?.take(80)}"
                )
                if (attempt + 1 < config.upstreamConnectAttempts) {
                    val jitter = 0.5 + java.security.SecureRandom().nextDouble()
                    try {
                        Thread.sleep(
                            (config.upstreamRetryBackoffMs * (attempt + 1) * jitter).toLong()
                        )
                    } catch (ignored: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        if (up == null) {
            sendError(clientTls, 502, "upstream connect failed after retries")
            return false
        }

        // Forward the request body: the peeked prefix (if any) first, then the rest via relayBody.
        // The tee (when dump is enabled) captures the full decoded body byte-exact, coexisting with
        // SseUsage's O(1) tap on the response side.
        var reqTee: ByteArray? = null
        if (bodyPrefixRemaining == null) {
            val r =
                relayBody(
                    clientTls.inputStream,
                    up.outputStream,
                    workingHeaders,
                    config.streamBlock,
                    tee = dump.enabled,
                    allowEof = false,
                )
            reqTee = r.teeBytes
        } else {
            up.outputStream.write(bodyPrefix)
            up.outputStream.flush()
            if (dump.enabled) reqTee = bodyPrefix
            if (bodyPrefixRemaining > 0) {
                val restHeaders = LinkedHashMap(workingHeaders)
                restHeaders["Content-Length"] = bodyPrefixRemaining.toString()
                val r =
                    relayBody(
                        clientTls.inputStream,
                        up.outputStream,
                        restHeaders,
                        config.streamBlock,
                        tee = dump.enabled,
                        allowEof = false,
                    )
                if (dump.enabled) {
                    reqTee = (reqTee ?: ByteArray(0)) + (r.teeBytes ?: ByteArray(0))
                }
            }
        }

        val statusBytes =
            try {
                recvLine(up.inputStream)
            } catch (e: IOException) {
                null
            } ?: return false
        val status = String(statusBytes, StandardCharsets.ISO_8859_1).trim()
        val rh = parseHeaders(up.inputStream) ?: return false
        sendHead(clientTls.outputStream, status, rh)

        val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        val noBody = method == "HEAD" || code in setOf(204, 304) || code in 100..199

        val meter =
            if (isMessages && !noBody && headerIgnoreCase(rh, "Content-Encoding") == null)
                SseUsage(mapper)
            else null

        val relayResult =
            if (noBody) RelayResult(null, false)
            else
                relayBody(
                    up.inputStream,
                    clientTls.outputStream,
                    rh,
                    config.streamBlock,
                    tee = dump.enabled,
                    allowEof = true,
                    tap = meter?.let { { blk: ByteArray -> it.feed(blk) } },
                )

        if (dump.enabled && dumpReqHeaders != null) {
            dump.writeAsync(
                machine = user,
                method = method,
                path = path,
                host = host,
                reqHeaders = dumpReqHeaders,
                reqBody = reqTee,
                statusLine = status,
                respHeaders = rh,
                respBody = relayResult.teeBytes,
            )
        }

        if (isMessages && !noBody) {
            DataplaneReporting.extractRateLimit(rh)?.let { reporting.reportRateLimit(user, it) }
            meter?.let {
                it.close()
                if (it.hasUsage()) {
                    reporting.reportUsage(
                        user,
                        it.model,
                        it.inputTokens,
                        it.outputTokens,
                        it.cacheReadTokens,
                        it.cacheWriteTokens,
                    )
                }
            }
        }

        if (relayResult.eofUsed) return false
        val connClose =
            headerIgnoreCase(workingHeaders, "Connection")?.lowercase() == "close" ||
                headerIgnoreCase(rh, "Connection")?.lowercase() == "close"
        return !connClose
    }

    private fun sendError(
        clientTls: SSLSocket,
        code: Int,
        message: String,
        errorType: String = "api_error",
    ) {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "type" to "error",
                    "error" to mapOf("type" to errorType, "message" to "ccproxy: $message"),
                )
            )
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason =
            mapOf(400 to "Bad Request", 502 to "Bad Gateway", 503 to "Service Unavailable")[code]
                ?: "Error"
        val head =
            "HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
        try {
            clientTls.outputStream.write(head.toByteArray(StandardCharsets.ISO_8859_1) + bodyBytes)
            clientTls.outputStream.flush()
        } catch (e: Exception) {}
    }
}
