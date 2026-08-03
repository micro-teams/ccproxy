/*
 *  Description: The reverse (engine→backend) half of credential persistence. The proxy-engine
 *               captures/injects tokens in its data plane, then upserts them here so the single
 *               durable store is Postgres instead of app_data/sessions files; on startup it lists
 *               them back to rebuild its in-memory registry. Not part of the public OpenAPI contract;
 *               authenticated by the shared engine secret header (@NoAuth + manual check), exactly
 *               like /internal/usage and /internal/session.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.credential

import app.microteams.ccproxy.common.config.CCProxyConfig
import org.rucca.cheese.auth.annotation.NoAuth
import org.rucca.cheese.common.error.BadRequestError
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** The engine's per-session token set — mirrors ccproxy_engine.py's _TOKEN_FIELDS. */
data class SessionCredentialDto(
    val proxyUser: String,
    val proxyPassword: String? = null,
    val accountProxy: String? = null,
    val realAccess: String? = null,
    val realRefresh: String? = null,
    val fakeAccess: String? = null,
    val fakeRefresh: String? = null,
    val expiresAt: Long? = null,
)

@RestController
class CredentialIngestController(
    private val credentialRepository: CredentialRepository,
    private val config: CCProxyConfig,
) {
    private fun checkSecret(secret: String?) {
        val expected = config.engine.controlSecret
        if (!expected.isNullOrBlank() && secret != expected) {
            throw BadRequestError("invalid engine secret")
        }
    }

    /** Engine dual-write: upsert a session's captured/injected tokens. Keyed by proxyUser. */
    @NoAuth
    @PostMapping("/internal/credential/session")
    @Transactional
    fun upsertSession(
        @RequestHeader(value = "X-Engine-Secret", required = false) secret: String?,
        @RequestBody dto: SessionCredentialDto,
    ): ResponseEntity<Unit> {
        checkSecret(secret)
        val existing =
            credentialRepository.findByScopeAndCredKey(CredentialScope.SESSION, dto.proxyUser)
        val row = existing ?: Credential(scope = CredentialScope.SESSION, credKey = dto.proxyUser)
        row.proxyPassword = dto.proxyPassword
        row.accountProxy = dto.accountProxy
        row.realAccess = dto.realAccess
        row.realRefresh = dto.realRefresh
        row.fakeAccess = dto.fakeAccess
        row.fakeRefresh = dto.fakeRefresh
        row.expiresAt = dto.expiresAt
        credentialRepository.save(row)
        return ResponseEntity.ok().build()
    }

    /** Engine startup: list every session credential to rebuild the in-memory registry. */
    @NoAuth
    @GetMapping("/internal/credential/sessions")
    fun listSessions(
        @RequestHeader(value = "X-Engine-Secret", required = false) secret: String?
    ): ResponseEntity<List<SessionCredentialDto>> {
        checkSecret(secret)
        val rows = credentialRepository.findAllByScope(CredentialScope.SESSION)
        return ResponseEntity.ok(
            rows.map {
                SessionCredentialDto(
                    proxyUser = it.credKey,
                    proxyPassword = it.proxyPassword,
                    accountProxy = it.accountProxy,
                    realAccess = it.realAccess,
                    realRefresh = it.realRefresh,
                    fakeAccess = it.fakeAccess,
                    fakeRefresh = it.fakeRefresh,
                    expiresAt = it.expiresAt,
                )
            }
        )
    }
}
