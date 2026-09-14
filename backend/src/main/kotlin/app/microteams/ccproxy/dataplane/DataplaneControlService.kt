/*
 *  Description: The collapsed control-API surface (item 11 of the port brief). ccproxy_engine.py
 *               exposed session register/login/credential/delete over an HTTP control API on :9000
 *               that EngineClient.kt calls; since the MITM logic now lives inside this same backend
 *               process, the equivalent operations become plain Spring service methods — no more
 *               loopback HTTP. NOTE: this service is the in-process equivalent for when the dataplane
 *               package is cut over; it does NOT replace EngineClient's calls to the real (Python)
 *               proxy-engine today — those must keep working untouched until cutover, per the task
 *               brief. Wiring callers over to this service is a later step.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import app.microteams.ccproxy.common.config.CCProxyConfig
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicReference
import org.springframework.stereotype.Service

data class DataplaneLoginResult(val hasCredential: Boolean, val expiresAt: Long?)

@Service
class DataplaneControlService(
    private val registry: SessionRegistry,
    private val sessionStore: SessionStore,
    private val config: CCProxyConfig,
    private val mapper: ObjectMapper,
) {
    /**
     * Live-toggleable model-gate policy (mirrors ControlHandler's GET/PUT /config). Starts from the
     * configured default so a fresh process isn't silently gated unless explicitly configured.
     */
    private val blockedModelFamilies =
        AtomicReference(ModelGate.parseBlockedFamilies(config.dataplane.blockedModelFamilies))

    fun getBlockedModelFamilies(): List<String> = blockedModelFamilies.get()

    fun setBlockedModelFamilies(raw: String) {
        blockedModelFamilies.set(ModelGate.parseBlockedFamilies(raw))
    }

    /** Register/update a machine's proxy session: its proxy-auth and the account egress proxy. */
    fun registerSession(proxyUser: String, proxyPassword: String, accountProxy: String?) {
        registry.put(proxyUser, proxyPassword, accountProxy)
    }

    fun removeSession(proxyUser: String) {
        registry.remove(proxyUser)
    }

    /**
     * Prime a login: the next OAuth token exchange this session makes will have its fake code
     * swapped for realCode, the real tokens captured, and fake tokens returned to the machine.
     */
    fun primeLogin(proxyUser: String, realCode: String, state: String?, fakeCode: String): Boolean {
        val sess = registry.get(proxyUser) ?: return false
        sess.pending = PendingLogin(realCode, state, fakeCode)
        return true
    }

    fun getLoginResult(proxyUser: String): DataplaneLoginResult? {
        val sess = registry.get(proxyUser) ?: return null
        return DataplaneLoginResult(sess.fakeAccess != null, sess.expiresAt)
    }

    /**
     * Inject a ready-made real OAuth token (the setup-token path), skipping the interactive /login
     * code exchange. Mints a fresh fake token in the same shape the exchange would have.
     */
    fun setCredential(
        proxyUser: String,
        accessToken: String,
        refreshToken: String?,
        expiresAt: Long?,
    ): String? {
        val sess = registry.get(proxyUser) ?: return null
        sess.realAccess = accessToken
        sess.realRefresh = refreshToken
        if (sess.fakeAccess == null) sess.fakeAccess = mintFakeAccess()
        if (sess.fakeRefresh == null && sess.realRefresh != null)
            sess.fakeRefresh = mintFakeRefresh()
        sess.expiresAt = expiresAt
        sess.pending = null
        sessionStore.persist(sess)
        return sess.fakeAccess
    }
}
