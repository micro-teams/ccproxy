/*
 *  Description: Kotlin port of the shared-quota model gate added to ccproxy_engine.py
 *               (commit 5732ad9, "reject opus/fable at the MITM to save shared quota"). Peeks the
 *               leading bytes of a /v1/messages request body — where the model name always sits —
 *               and decides whether to reject BEFORE any upstream connection is opened, so a blocked
 *               call costs nothing. Kept pure/regex-based here so the decision is unit-testable
 *               without sockets; the byte-peeking I/O lives in the streaming relay layer.
 *
 *               BLOCKED_MODEL_FAMILIES semantics preserved: empty = gate off (runtime policy, not a
 *               code default); case-insensitive substring match against the model name; any ambiguity
 *               (model not found in the peeked prefix) FAILS OPEN — never wrongly rejects.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

object ModelGate {
    private val MODEL_RE: Pattern = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]{1,80})\"")

    fun parseBlockedFamilies(raw: String?): List<String> =
        raw?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()

    /**
     * Find the model name in a (possibly truncated) prefix of the request body, or null if absent.
     */
    fun extractModel(bodyPrefix: ByteArray): String? {
        val text = String(bodyPrefix, StandardCharsets.US_ASCII)
        val m = MODEL_RE.matcher(text)
        return if (m.find()) m.group(1).lowercase() else null
    }

    /**
     * Null = allow (fails open when the model can't be determined). Non-null = the reject message.
     */
    fun reject(model: String?, blockedFamilies: List<String>): String? {
        if (blockedFamilies.isEmpty() || model == null) return null
        val lower = model.lowercase()
        if (blockedFamilies.any { lower.contains(it) }) {
            return "model \"$model\" is temporarily disabled to save the shared quota — " +
                "please use a sonnet model instead"
        }
        return null
    }
}
