/*
 *  Description: Kotlin port of ccproxy_engine.py's run_proxy/handle_client/tunnel/forward_plain_http —
 *               the CONNECT-proxy listener itself (item 9). Parses the CONNECT line + Proxy-
 *               Authorization Basic header, looks up the session, and either TLS-terminates + MITMs
 *               (host in mitmDomains AND session/password match) or tunnels raw bytes DIRECT
 *               (everything else) — direct tunnelling deliberately bypasses the account's egress
 *               proxy; only Anthropic traffic uses that egress. A non-CONNECT request (plain-HTTP
 *               forward-proxy, e.g. to a newapi-style gateway) is passed through directly too.
 *
 *               Gated behind ccproxy.dataplane.enabled (default false): this in-process engine is a
 *               parallel implementation for shadow-mode testing, not yet the live data plane.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import app.microteams.ccproxy.common.config.CCProxyConfig
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ProxyServer(
    private val config: CCProxyConfig,
    private val registry: SessionRegistry,
    private val sessionStore: SessionStore,
    private val mapper: ObjectMapper,
    private val reporting: DataplaneReporting,
    private val controlService: DataplaneControlService,
) {
    private val log = LoggerFactory.getLogger(ProxyServer::class.java)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private var serverSocket: ServerSocket? = null
    private lateinit var certAuthority: CertAuthority
    private lateinit var mitmHandler: MitmHandler

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val dp = config.dataplane
        if (!dp.enabled) {
            log.info(
                "dataplane: disabled (ccproxy.dataplane.enabled=false); not starting :${dp.proxyPort}"
            )
            return
        }
        certAuthority = CertAuthority(dp.caCertPath, dp.caKeyPath, dp.certsDir)
        mitmHandler = MitmHandler(dp, mapper, reporting, controlService)
        val ss = ServerSocket(dp.proxyPort)
        serverSocket = ss
        log.info("dataplane: MITM proxy listening on :${dp.proxyPort}")
        executor.submit {
            while (!ss.isClosed) {
                try {
                    val c = ss.accept()
                    executor.submit { handleClient(c) }
                } catch (e: Exception) {
                    if (!ss.isClosed) log.warn("dataplane: accept failed: ${e.message}")
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        // Graceful shutdown: stop accepting new connections. In-flight sessions' DB state was
        // already write-through persisted (SessionStore.persist), so a redeploy here loses at most
        // in-flight requests, never durable credential state — matching the task brief's
        // requirement.
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        executor.shutdown()
    }

    private fun handleClient(cs: Socket) {
        try {
            UpstreamConnector.setTcpKeepAlive(cs)
            val input = cs.getInputStream()
            val lineBytes = recvLine(input) ?: return
            val line = String(lineBytes, StandardCharsets.ISO_8859_1).trim()
            val parts = line.split(" ")
            if (parts.size < 3) return
            if (parts[0] != "CONNECT") {
                forwardPlainHttp(cs, parts)
                return
            }
            val target = parts[1]
            val headers = parseHeaders(input) ?: return
            val host = target.substringBefore(":")
            val port = target.substringAfter(":", "443").toIntOrNull() ?: 443
            val (user, pw) = parseProxyAuth(headers)
            var sess = user?.let { registry.get(it) }
            if (sess == null && user != null) {
                sess = sessionStore.loadOne(user)
            }
            val dp = config.dataplane
            if (host in dp.mitmDomains && sess != null && sess.proxyPassword == pw) {
                cs.getOutputStream()
                    .write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                cs.getOutputStream().flush()
                val ctx = certAuthority.contextFor(host)
                val clientTls = ctx.socketFactory.createSocket(cs, host, port, true) as SSLSocket
                clientTls.useClientMode = false
                clientTls.startHandshake()
                mitmHandler.handleMitm(clientTls, host, port, sess, user!!)
            } else {
                // Not MITM'd (non-Anthropic domain, or unauthenticated): tunnel DIRECT, never
                // through
                // the session's account egress proxy — see class doc.
                cs.getOutputStream()
                    .write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                cs.getOutputStream().flush()
                val up = Socket()
                up.connect(java.net.InetSocketAddress(host, port), 30_000)
                UpstreamConnector.setTcpKeepAlive(up)
                tunnel(cs, up)
            }
        } catch (e: Exception) {
            log.debug("dataplane client: ${e.message?.take(100)}")
        } finally {
            try {
                cs.close()
            } catch (e: Exception) {}
        }
    }

    private fun parseProxyAuth(headers: Map<String, String>): Pair<String?, String?> {
        val v = headerIgnoreCase(headers, "Proxy-Authorization") ?: return null to null
        return try {
            val (scheme, blob) = v.split(" ", limit = 2)
            if (!scheme.equals("Basic", true)) return null to null
            val decoded = String(Base64.getDecoder().decode(blob), StandardCharsets.UTF_8)
            val i = decoded.indexOf(':')
            if (i < 0) decoded to "" else decoded.substring(0, i) to decoded.substring(i + 1)
        } catch (e: Exception) {
            null to null
        }
    }

    /**
     * Forward a plain-HTTP proxy request (absolute-form target) straight to its origin, and relay
     * the response back — never MITM'd or metered, just rewritten to origin-form and tunnelled.
     */
    private fun forwardPlainHttp(cs: Socket, parts: List<String>) {
        val method = parts[0]
        // parts[1] is the absolute-form request-target; parts[2] is the HTTP version token.
        val requestTarget = parts[1]
        val version = parts[2]
        val uri =
            try {
                URI(requestTarget)
            } catch (e: Exception) {
                return
            }
        if (uri.scheme != "http" || uri.host == null) return
        var origin = uri.rawPath.ifEmpty { "/" }
        if (uri.rawQuery != null) origin += "?" + uri.rawQuery
        val port = if (uri.port == -1) 80 else uri.port
        val up = Socket()
        up.connect(java.net.InetSocketAddress(uri.host, port), 30_000)
        up.getOutputStream()
            .write("$method $origin $version\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        up.getOutputStream().flush()
        tunnel(cs, up)
    }

    /** Raw bidirectional byte splice between two sockets until either side closes or goes idle. */
    private fun tunnel(a: Socket, b: Socket) {
        val t1 = Thread {
            try {
                a.getInputStream().copyTo(b.getOutputStream())
            } catch (e: Exception) {}
            try {
                b.shutdownOutput()
            } catch (e: Exception) {}
        }
        val t2 = Thread {
            try {
                b.getInputStream().copyTo(a.getOutputStream())
            } catch (e: Exception) {}
            try {
                a.shutdownOutput()
            } catch (e: Exception) {}
        }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        try {
            a.close()
        } catch (e: Exception) {}
        try {
            b.close()
        } catch (e: Exception) {}
    }
}
