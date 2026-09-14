/*
 *  Description: Kotlin port of ccproxy_engine.py's refresh-absorption trio (#67): parse_refresh_grant,
 *               local_refresh_response, absorb_refresh. A machine's refresh_token grant carrying its
 *               fake refresh token is answered LOCALLY with the stable fake pair whenever the real
 *               chain still has > refreshMargin of life, or is in refreshBackoff after a recent
 *               failure — the real chain is only refreshed upstream, single-flight per session, when
 *               actually needed. This is what lets many concurrent claude processes on one machine
 *               share one real refresh without racing or invalidating each other.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * A synthesized or passed-through HTTP response: status line text (e.g. "200 OK"), headers, body.
 */
data class SwapResponse(
    val statusLine: String,
    val headers: MutableMap<String, String>,
    val body: String,
)

object RefreshAbsorption {
    /**
     * True iff this request is the machine's own token refresh: a refresh_token grant carrying this
     * machine's issued fake refresh token. Keyed on the BODY, not the URL, so it holds across CLI
     * versions and token-endpoint hosts. Tries JSON first, then form encoding.
     */
    fun parseRefreshGrant(body: String?, sess: Session, mapper: ObjectMapper): Boolean {
        val fakeRefresh = sess.fakeRefresh ?: return false
        if (body.isNullOrEmpty()) return false
        try {
            val obj = mapper.readTree(body)
            if (obj is ObjectNode) {
                return obj.get("grant_type")?.asText() == "refresh_token" &&
                    obj.get("refresh_token")?.asText() == fakeRefresh
            }
        } catch (e: Exception) {
            // fall through to form-encoded parse
        }
        val q = parseFormEncoded(body)
        return q["grant_type"] == "refresh_token" && q["refresh_token"] == fakeRefresh
    }

    private fun parseFormEncoded(body: String): Map<String, String> =
        body
            .split("&")
            .mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null
                else
                    java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                        java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
            .toMap()

    /**
     * A locally synthesized token response carrying the machine's stable fake pair. Non-token
     * fields mirror the last real grant when held; the fallback shape matches the real endpoint's,
     * per Python's comment ("validated against claude v2.1.233").
     */
    fun localRefreshResponse(sess: Session, mapper: ObjectMapper): SwapResponse {
        var expiresIn = 60L
        sess.expiresAt?.let { expiresIn = maxOf(60L, it - Instant.now().epochSecond) }
        val obj: ObjectNode =
            (sess.tokenExtras?.let { mapper.valueToTree<ObjectNode>(it) }
                ?: mapper.createObjectNode().apply {
                    put("token_type", "Bearer")
                    put("scope", "user:inference user:profile")
                })
        obj.put("access_token", sess.fakeAccess)
        sess.fakeRefresh?.let { obj.put("refresh_token", it) }
        obj.put("expires_in", expiresIn)
        return SwapResponse(
            "200 OK",
            mutableMapOf("Content-Type" to "application/json"),
            mapper.writeValueAsString(obj),
        )
    }

    /**
     * Answer a machine's refresh with its stable fake pair, refreshing the real chain upstream only
     * when it nears expiry, single-flight per session (guarded by [Session.refreshLock]) however
     * many holders ask at once.
     *
     * [doUpstream] performs the actual real-chain refresh HTTP exchange (swap+send+read) and
     * returns (statusLine, headers, rawBody) or null on transport failure — kept as a lambda so the
     * decision logic here is unit-testable without sockets.
     */
    fun absorbRefresh(
        sess: Session,
        refreshMarginSeconds: Long,
        refreshBackoffSeconds: Long,
        mapper: ObjectMapper,
        nowFn: () -> Long = { Instant.now().epochSecond },
        doUpstream: () -> Triple<String, MutableMap<String, String>, String>?,
    ): SwapResponse {
        sess.refreshLock.lock()
        try {
            val now = nowFn()
            val expiresAt = sess.expiresAt
            if (expiresAt != null && expiresAt - now > refreshMarginSeconds) {
                return localRefreshResponse(sess, mapper)
            }
            if (now - sess.lastRefreshFail < refreshBackoffSeconds) {
                return localRefreshResponse(sess, mapper)
            }
            val resp =
                try {
                    doUpstream()
                } catch (e: Exception) {
                    null
                }
            if (resp == null) {
                // Transient: the real chain may well still be valid — keep the machine on it and
                // let
                // the client's own retry find upstream recovered.
                sess.lastRefreshFail = now
                return localRefreshResponse(sess, mapper)
            }
            val (status, rh, raw) = resp
            val obj =
                try {
                    mapper.readTree(raw)
                } catch (e: Exception) {
                    null
                }
            if (obj is ObjectNode && obj.has("access_token")) {
                TokenSwap.captureRealTokens(sess, obj, mapper)
                return localRefreshResponse(sess, mapper)
            }
            // A definitive upstream refusal (e.g. 400 invalid_grant) passes through honestly —
            // after
            // the leak-safety swap.
            sess.lastRefreshFail = now
            return SwapResponse(status, rh, TokenSwap.swapResponseBody(raw, sess, mapper) ?: raw)
        } finally {
            sess.refreshLock.unlock()
        }
    }
}
