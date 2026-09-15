/*
 *  Description: Token-swap primitives — the Kotlin port of ccproxy_engine.py's swap_request_body /
 *               swap_auth_header / capture_real_tokens / swap_response_body. Kept as pure functions
 *               operating on a Session and an ObjectMapper so they're unit-testable without any I/O.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.SecureRandom
import java.time.Instant

private val secureRandom = SecureRandom()

private fun randomHex(bytes: Int): String {
    val b = ByteArray(bytes)
    secureRandom.nextBytes(b)
    return b.joinToString("") { "%02x".format(it) }
}

fun mintFakeAccess(): String = "sk-ant-oat01-" + randomHex(32)

fun mintFakeRefresh(): String = "sk-ant-ort01-" + randomHex(32)

object TokenSwap {
    /**
     * fake code/access/refresh -> real, in a request body's raw text. Returns the body unchanged if
     * nothing matched (mirrors swap_request_body's changed-flag short-circuit).
     */
    fun swapRequestBody(body: String?, sess: Session): String? {
        if (body.isNullOrEmpty()) return body
        var text = body
        var changed = false
        val pending = sess.pending
        if (
            pending?.fakeCode != null && pending.realCode != null && text.contains(pending.fakeCode)
        ) {
            text = text.replace(pending.fakeCode, pending.realCode)
            changed = true
        }
        val fa = sess.fakeAccess
        val ra = sess.realAccess
        if (fa != null && ra != null && text.contains(fa)) {
            text = text.replace(fa, ra)
            changed = true
        }
        val fr = sess.fakeRefresh
        val rr = sess.realRefresh
        if (fr != null && rr != null && text.contains(fr)) {
            text = text.replace(fr, rr)
            changed = true
        }
        return if (changed) text else body
    }

    /** fake access -> real, in an Authorization header value. */
    fun swapAuthHeader(value: String?, sess: Session): String? {
        val fa = sess.fakeAccess
        val ra = sess.realAccess
        if (value == null || fa == null || ra == null || !value.contains(fa)) return value
        return value.replace(fa, ra)
    }

    /**
     * Take the real pair (+ expiry + extras) from a real token response object. The fake pair is
     * minted only if the machine doesn't have one yet — an existing pair is STABLE (#67): tokens
     * already in holders' hands survive every rotation and re-login of the real chain.
     */
    fun captureRealTokens(sess: Session, obj: ObjectNode, mapper: ObjectMapper) {
        obj.get("access_token")?.takeIf { !it.isNull }?.let { sess.realAccess = it.asText() }
        // A refresh response may omit refresh_token (= keep using the old one); never null it out.
        obj.get("refresh_token")?.takeIf { !it.isNull }?.let { sess.realRefresh = it.asText() }
        if (sess.fakeAccess == null) sess.fakeAccess = mintFakeAccess()
        if (sess.fakeRefresh == null && sess.realRefresh != null)
            sess.fakeRefresh = mintFakeRefresh()
        obj.get("expires_in")
            ?.takeIf { !it.isNull }
            ?.let {
                sess.expiresAt = Instant.now().epochSecond + it.asLong()
            }
        val extras = LinkedHashMap<String, Any?>()
        val fields = obj.fields()
        while (fields.hasNext()) {
            val (k, v) = fields.next()
            if (k !in setOf("access_token", "refresh_token", "expires_in")) {
                extras[k] = mapper.convertValue(v, Any::class.java)
            }
        }
        sess.tokenExtras = extras
        sess.pending = null
    }

    /**
     * Capture real tokens on the oauth/token response and return the machine's fake tokens. Returns
     * the (possibly rewritten) body text, or the original body unchanged if it wasn't a token
     * response and nothing needed swapping.
     */
    fun swapResponseBody(body: String?, sess: Session, mapper: ObjectMapper): String? {
        if (body.isNullOrEmpty()) return body
        val obj =
            try {
                mapper.readTree(body)
            } catch (e: Exception) {
                null
            }
        if (obj is ObjectNode && obj.has("access_token")) {
            captureRealTokens(sess, obj, mapper)
            obj.put("access_token", sess.fakeAccess)
            if (sess.realRefresh != null && sess.fakeRefresh != null) {
                obj.put("refresh_token", sess.fakeRefresh)
            }
            return mapper.writeValueAsString(obj)
        }
        // Steady-state: never leak a real token that might echo back.
        var text = body
        var changed = false
        val ra = sess.realAccess
        val fa = sess.fakeAccess
        if (ra != null && fa != null && text.contains(ra)) {
            text = text.replace(ra, fa)
            changed = true
        }
        val rr = sess.realRefresh
        val fr = sess.fakeRefresh
        if (rr != null && fr != null && text.contains(rr)) {
            text = text.replace(rr, fr)
            changed = true
        }
        return if (changed) text else body
    }
}
