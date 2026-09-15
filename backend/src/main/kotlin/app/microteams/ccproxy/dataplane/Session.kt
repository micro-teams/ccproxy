/*
 *  Description: In-memory session registry — the Kotlin port of ccproxy_engine.py's Session/Registry
 *               classes. One Session per machine (keyed by proxyUser) holds the real<->fake token
 *               pairs, the pending login code-swap, and the single-flight refresh lock. Real tokens
 *               live ONLY here and in the `credential` table; they are never handed back over any
 *               control surface.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.springframework.stereotype.Component

/** {"realCode","state","fakeCode"} for a pending OAuth code-exchange swap. */
data class PendingLogin(val realCode: String?, val state: String?, val fakeCode: String?)

/**
 * One machine's session state. Mutable fields are guarded by [lock] for the token fields and by
 * [refreshLock] for the refresh single-flight (mirrors Python's per-session refresh_lock — #67).
 */
class Session(
    @Volatile var proxyPassword: String?,
    @Volatile var accountProxy: String?,
    val user: String,
) {
    val lock = Any()
    val refreshLock: ReentrantLock = ReentrantLock()

    @Volatile var realAccess: String? = null
    @Volatile var realRefresh: String? = null
    @Volatile var fakeAccess: String? = null
    @Volatile var fakeRefresh: String? = null
    /** epoch seconds — the REAL access token's expiry. */
    @Volatile var expiresAt: Long? = null
    @Volatile var pending: PendingLogin? = null
    @Volatile var lastRefreshFail: Long = 0L
    /**
     * Non-token fields of the last real token response (scope etc.), echoed into locally
     * synthesized refresh answers. In-memory only — after a restart the lab-validated default in
     * [RefreshAbsorption] covers until the next upstream capture.
     */
    @Volatile var tokenExtras: Map<String, Any?>? = null
}

/** Thread-safe registry of sessions keyed by proxyUser. Mirrors ccproxy_engine.py's Registry. */
@Component
class SessionRegistry {
    private val byUser = ConcurrentHashMap<String, Session>()

    fun put(user: String, proxyPassword: String?, accountProxy: String?): Session =
        byUser.compute(user) { _, existing ->
            if (existing == null) {
                Session(proxyPassword, accountProxy, user)
            } else {
                existing.proxyPassword = proxyPassword
                existing.accountProxy = accountProxy
                existing
            }
        }!!

    fun get(user: String): Session? = byUser[user]

    fun remove(user: String) {
        byUser.remove(user)
    }

    fun all(): Collection<Session> = byUser.values
}
