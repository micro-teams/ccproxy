/*
 *  Description: Kotlin port of ccproxy_engine.py's upstream_tls + set_tcp_keepalive + the
 *               UPSTREAM_CONNECT_ATTEMPTS retry loop from forward_streaming. Opens a TLS connection to
 *               Anthropic THROUGH the session's account egress proxy (CONNECT tunnel), with jittered
 *               retry on a fresh connection — the egress TLS handshake fails intermittently (~1/3 in
 *               bad windows) with a bare EOF, and a reconnect almost always succeeds (see memory
 *               ccproxy-upstream-connect-eof-retry). This retry covers ONLY the connect phase, exactly
 *               like Python: a reset occurring DURING body relay (after headers are already sent to
 *               the client) is NOT retried here — that is a known, separately-tracked gap, not
 *               silently "fixed" by this port.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private val trustAllContext: SSLContext by lazy {
    val trustAll =
        object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out java.security.cert.X509Certificate>?,
                authType: String?,
            ) {}

            override fun checkServerTrusted(
                chain: Array<out java.security.cert.X509Certificate>?,
                authType: String?,
            ) {}

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
    }
}

object UpstreamConnector {
    fun setTcpKeepAlive(sock: Socket, keepIdleSeconds: Int = 60) {
        try {
            sock.keepAlive = true
        } catch (e: java.net.SocketException) {
            // best-effort, matches Python's bare except OSError
        }
    }

    /**
     * Open a TLS connection to host:port, tunnelling through accountProxy ("http://h:p") if set.
     */
    fun connectTls(
        host: String,
        port: Int,
        accountProxy: String?,
        connectTimeoutMs: Int,
        socketTimeoutMs: Int,
    ): SSLSocket {
        val raw: Socket
        if (!accountProxy.isNullOrBlank()) {
            val stripped = accountProxy.substringAfter("://")
            val phost = stripped.substringBefore(":")
            val pport = stripped.substringAfter(":").toInt()
            raw = Socket()
            raw.connect(InetSocketAddress(phost, pport), connectTimeoutMs)
            raw.getOutputStream()
                .write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray())
            raw.getOutputStream().flush()
            val respBuf = java.io.ByteArrayOutputStream()
            val inp = raw.getInputStream()
            while (true) {
                val b = inp.read()
                if (b < 0) throw IOException("egress proxy closed during CONNECT")
                respBuf.write(b)
                val s = respBuf.toByteArray()
                if (
                    s.size >= 4 &&
                        String(s, s.size - 4, 4, StandardCharsets.ISO_8859_1) == "\r\n\r\n"
                )
                    break
                if (s.size > 16384) throw IOException("egress proxy CONNECT response too large")
            }
            val statusLine =
                String(respBuf.toByteArray(), StandardCharsets.ISO_8859_1).substringBefore("\r\n")
            if (" 200 " !in statusLine) {
                throw IOException("egress proxy refused CONNECT: ${statusLine.take(80)}")
            }
        } else {
            raw = Socket()
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
        }
        setTcpKeepAlive(raw)
        raw.soTimeout = socketTimeoutMs
        val factory = trustAllContext.socketFactory
        val tls = factory.createSocket(raw, host, port, true) as SSLSocket
        tls.soTimeout = socketTimeoutMs
        tls.startHandshake()
        return tls
    }

    /**
     * Connect with jittered retry, matching forward_streaming's UPSTREAM_CONNECT_ATTEMPTS loop.
     * [onAttemptFailed] is called (attempt index, exception) between retries for logging.
     */
    fun connectWithRetry(
        host: String,
        port: Int,
        accountProxy: String?,
        connectTimeoutMs: Int,
        socketTimeoutMs: Int,
        attempts: Int,
        backoffMs: Long,
        onAttemptFailed: (Int, Exception) -> Unit = { _, _ -> },
    ): SSLSocket? {
        val random = SecureRandom()
        for (attempt in 0 until attempts) {
            try {
                return connectTls(host, port, accountProxy, connectTimeoutMs, socketTimeoutMs)
            } catch (e: Exception) {
                onAttemptFailed(attempt, e)
                if (attempt + 1 < attempts) {
                    // Jitter in [0.5, 1.5): failures hit every machine at once, so un-jittered
                    // backoff
                    // would have them all retry in lockstep and hammer the upstream in sync.
                    val jitter = 0.5 + random.nextDouble()
                    val delayMs = (backoffMs * (attempt + 1) * jitter).toLong()
                    try {
                        Thread.sleep(delayMs)
                    } catch (ignored: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }
        return null
    }
}
