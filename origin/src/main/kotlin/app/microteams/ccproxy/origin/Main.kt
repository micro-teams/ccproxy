/*
 * The origin process: the server end of the MultiPath substrate, sitting in front of the backend's
 * existing MITM proxy port.
 *
 * This is a pure transport swap, not a protocol change (2026-09-15: nictheboy pointed at
 * micro-teams' origin/ + cli/internal/host/host.go as the model to copy after an earlier draft of
 * this reinvented session lookup on top of a "ticket"). The handler below does exactly one thing:
 * splice the accepted mux stream to backend:3128, the SAME port a machine reaches today by dialling
 * TCP directly. Whatever arrives on the stream — the CONNECT line, the Proxy-Authorization Basic
 * header, the TLS ClientHello — is bytes ProxyServer already knows how to read; this process never
 * looks at any of it. One service, named "proxy", and nothing else: a client cannot name an address,
 * only that one name, so there is no open-relay surface to reason about.
 *
 * Mirror of micro-teams' origin/src/main/kotlin/app/microteams/origin/Main.kt.
 */
package app.microteams.ccproxy.origin

import app.microteams.multipath.Origin
import app.microteams.multipath.redundant.RedundantOptions
import java.net.ServerSocket

/** The one name a connector opens a stream to. */
const val PROXY_SERVICE = "proxy"

private fun env(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

fun main() {
    val port = env("CCPROXY_ORIGIN_PORT", "9444").toInt()
    val proxyHost = env("CCPROXY_PROXY_HOST", "backend")
    val proxyPort = env("CCPROXY_PROXY_PORT", "3128").toInt()
    val linkPath = env("CCPROXY_LINK_PATH", "/link")

    // The MAXIMUM number of links one client may attach — not the number of lines this deployment
    // publishes. See micro-teams' Main.kt for why this wants to be generous rather than exact.
    val maxLines = env("CCPROXY_MAX_LINES", "16").toInt()

    // No sslContext: nginx terminates TLS in front of this process, so what arrives here is a
    // plaintext WebSocket on the compose network (mirrors micro-teams' origin).
    val origin = Origin(ServerSocket(port), RedundantOptions(n = maxLines), null, linkPath)

    println(
        "multipath origin listening on $port, service \"$PROXY_SERVICE\" at $proxyHost:$proxyPort, " +
            "links at $linkPath, max $maxLines links per client",
    )
    System.out.flush()
    origin.serve(mapOf(PROXY_SERVICE to Origin.dialService(proxyHost, proxyPort)))
}
