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
import app.microteams.ccproxy.machine.MachineRepository
import org.rucca.cheese.auth.annotation.NoAuth
import org.rucca.cheese.common.error.BadRequestError
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
    private val machineRepository: MachineRepository,
) {
    private fun checkSecret(secret: String?) {
        val expected = config.engine.controlSecret
        if (!expected.isNullOrBlank() && secret != expected) {
            throw BadRequestError("invalid engine secret")
        }
    }

    private fun toDto(c: Credential) =
        SessionCredentialDto(
            proxyUser = c.credKey,
            proxyPassword = c.proxyPassword,
            accountProxy = c.accountProxy,
            realAccess = c.realAccess,
            realRefresh = c.realRefresh,
            fakeAccess = c.fakeAccess,
            fakeRefresh = c.fakeRefresh,
            expiresAt = c.expiresAt,
        )

    /** Engine dual-write: upsert a session's captured/injected tokens. Keyed by proxyUser. */
    @NoAuth
    @PostMapping("/internal/credential/session")
    @Transactional
    fun upsertSession(
        @RequestHeader(value = "X-Engine-Secret", required = false) secret: String?,
        @RequestBody dto: SessionCredentialDto,
    ): ResponseEntity<Unit> {
        checkSecret(secret)
        // A machine that no longer exists is a revoked ticket: refuse the write so an in-flight
        // capture racing a revocation cannot re-plant the credential the delete just removed.
        machineRepository.findByProxyUser(dto.proxyUser) ?: return ResponseEntity.notFound().build()
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

    /**
     * Engine startup: list every session credential to rebuild the in-memory registry. Only
     * sessions whose machine still exists are returned — findByProxyUser is @SQLRestriction
     * -filtered, so a deleted (= revoked) machine's leftover row can never resurrect its ticket
     * across an engine restart. (Rows written before deleteMachine started soft-deleting them are
     * caught here too.)
     */
    @NoAuth
    @GetMapping("/internal/credential/sessions")
    fun listSessions(
        @RequestHeader(value = "X-Engine-Secret", required = false) secret: String?
    ): ResponseEntity<List<SessionCredentialDto>> {
        checkSecret(secret)
        return ResponseEntity.ok(
            credentialRepository
                .findAllByScope(CredentialScope.SESSION)
                .filter { machineRepository.findByProxyUser(it.credKey) != null }
                .map { toDto(it) }
        )
    }

    /**
     * Engine lazy self-heal: fetch one session's credential (proxy auth + captured tokens) from the
     * DB on a cache miss. 404 when the machine has no credential yet (registered but never logged
     * in) — the engine then falls back to /internal/session for just the proxy auth.
     */
    @NoAuth
    @GetMapping("/internal/credential/session/{proxyUser}")
    fun getSession(
        @RequestHeader(value = "X-Engine-Secret", required = false) secret: String?,
        @PathVariable proxyUser: String,
    ): ResponseEntity<SessionCredentialDto> {
        checkSecret(secret)
        // Same live-machine guard as the startup list: never hand out a revoked machine's tokens.
        machineRepository.findByProxyUser(proxyUser) ?: return ResponseEntity.notFound().build()
        val row =
            credentialRepository.findByScopeAndCredKey(CredentialScope.SESSION, proxyUser)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(toDto(row))
    }
}
