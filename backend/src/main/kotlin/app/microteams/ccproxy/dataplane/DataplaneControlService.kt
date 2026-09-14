/*
 *  Description: The collapsed control-API surface (item 11 of the port brief). ccproxy_engine.py
 *               exposed session register/login/credential/delete over an HTTP control API on :9000
 *               that EngineClient.kt used to call; the MITM logic now lives inside this same backend
 *               process, so the equivalent operations are plain Spring service methods — no more
 *               loopback HTTP. This IS the live control surface: EngineClient.kt and the Python
 *               proxy-engine are gone (2026-09-14 cutover).
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
     * Throws if the session isn't registered yet (mirrors the old engine's 404), since callers
     * always registerSession() first and treat this as a hard failure otherwise.
     */
    fun primeLogin(proxyUser: String, realCode: String, state: String?, fakeCode: String) {
        val sess =
            registry.get(proxyUser)
                ?: throw IllegalStateException("no dataplane session for $proxyUser")
        sess.pending = PendingLogin(realCode, state, fakeCode)
    }

    /** hasCredential=false/expiresAt=null for an unregistered session, never throws. */
    fun getLoginResult(proxyUser: String): DataplaneLoginResult {
        val sess = registry.get(proxyUser) ?: return DataplaneLoginResult(false, null)
        return DataplaneLoginResult(sess.fakeAccess != null, sess.expiresAt)
    }

    /**
     * Inject a ready-made real OAuth token (the setup-token path), skipping the interactive /login
     * code exchange. Mints a fresh fake token in the same shape the exchange would have. Throws if
     * the session isn't registered yet (callers always registerSession() first).
     */
    fun setCredential(
        proxyUser: String,
        accessToken: String,
        refreshToken: String?,
        expiresAt: Long?,
    ): String {
        val sess =
            registry.get(proxyUser)
                ?: throw IllegalStateException("no dataplane session for $proxyUser")
        sess.realAccess = accessToken
        sess.realRefresh = refreshToken
        if (sess.fakeAccess == null) sess.fakeAccess = mintFakeAccess()
        if (sess.fakeRefresh == null && sess.realRefresh != null)
            sess.fakeRefresh = mintFakeRefresh()
        sess.expiresAt = expiresAt
        sess.pending = null
        sessionStore.persist(sess)
        return sess.fakeAccess!!
    }
}
