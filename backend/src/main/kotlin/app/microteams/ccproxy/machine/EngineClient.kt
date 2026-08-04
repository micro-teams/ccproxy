/*
 *  Description: The backend's client for the proxy-engine control API. The backend is the control
 *               plane; the proxy-engine holds the real↔fake credential mappings and does the MITM.
 *               Sessions are keyed by a machine's proxy-auth username (proxyUser). The engine never
 *               hands a real token back to the backend — the backend only tells it what to swap and
 *               reads back non-secret status (whether a credential exists and when it expires).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

import app.microteams.ccproxy.common.config.CCProxyConfig
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class LoginResult(val hasCredential: Boolean, val expiresAt: Long?)

@Component
class EngineClient(private val config: CCProxyConfig, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(EngineClient::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Register/update a machine's proxy session: its proxy-auth and the account egress proxy. */
    fun registerSession(proxyUser: String, proxyPassword: String, accountProxy: String) {
        send(
            "PUT",
            "/sessions/$proxyUser",
            mapOf("proxyPassword" to proxyPassword, "accountProxy" to accountProxy),
        )
    }

    fun removeSession(proxyUser: String) {
        send("DELETE", "/sessions/$proxyUser", null)
    }

    /**
     * Prime a login: the next OAuth token exchange this session makes will have its fake code
     * swapped for [realCode], the real tokens captured, and fake tokens returned to the machine.
     */
    fun primeLogin(proxyUser: String, realCode: String, state: String?, fakeCode: String) {
        send(
            "POST",
            "/sessions/$proxyUser/login",
            mapOf("realCode" to realCode, "state" to state, "fakeCode" to fakeCode),
        )
    }

    /** Read back whether the machine now holds a credential and when it expires. */
    fun getLoginResult(proxyUser: String): LoginResult {
        val body = send("GET", "/sessions/$proxyUser/login", null)
        return mapper.readValue(body, LoginResult::class.java)
    }

    /**
     * Inject a ready-made real OAuth token (from `claude setup-token`) as this session's
     * credential, skipping the interactive /login code exchange. The engine stores it as the real
     * token, mints a fake token in the same shape a login would have, and returns that fake — which
     * the backend then writes into the machine's CLAUDE_CODE_OAUTH_TOKEN. A setup-token has no
     * refresh_token; that is tolerated engine-side.
     */
    fun setCredential(proxyUser: String, accessToken: String, expiresAt: Long?): String {
        val body =
            send(
                "PUT",
                "/sessions/$proxyUser/credential",
                mapOf("accessToken" to accessToken, "expiresAt" to expiresAt),
            )
        return mapper.readTree(body).get("fakeAccess").asText()
    }

    private fun send(method: String, path: String, body: Any?): String {
        val builder =
            HttpRequest.newBuilder()
                .uri(URI.create(config.engine.controlUrl.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
        config.engine.controlSecret?.let { builder.header("X-Engine-Secret", it) }
        val publisher =
            if (body != null) HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
            else HttpRequest.BodyPublishers.noBody()
        builder.method(method, publisher)
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 300) {
            throw IllegalStateException(
                "engine $method $path failed ${resp.statusCode()}: ${resp.body()}"
            )
        }
        return resp.body()
    }
}
