/*
 *  Description: Internal ingest endpoint the proxy-engine POSTs metered usage to. Not part of the
 *               public OpenAPI contract; authenticated by the shared engine secret header rather
 *               than the role framework (hence @NoAuth + a manual check).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.usage

import app.microteams.ccproxy.account.AccountRepository
import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.machine.MachineRepository
import org.rucca.cheese.auth.annotation.NoAuth
import org.rucca.cheese.common.error.BadRequestError
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class UsageReport(
    val proxyUser: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
)

@RestController
class EngineIngestController(
    private val usageService: UsageService,
    private val machineRepository: MachineRepository,
    private val accountRepository: AccountRepository,
    private val config: CCProxyConfig,
) {
    private fun checkSecret(secret: String?) {
        val expected = config.engine.controlSecret
        if (!expected.isNullOrBlank() && secret != expected) {
            throw BadRequestError("invalid engine secret")
        }
    }

    @NoAuth
    @PostMapping("/internal/usage")
    fun ingest(
        @RequestHeader(value = "X-Engine-Secret", required = false) secret: String?,
        @RequestBody report: UsageReport,
    ): ResponseEntity<Unit> {
        checkSecret(secret)
        usageService.record(
            report.proxyUser,
            report.model,
            report.inputTokens,
            report.outputTokens,
            report.cacheReadTokens,
            report.cacheWriteTokens,
        )
        return ResponseEntity.ok().build()
    }

    /**
     * Lets the proxy-engine rebuild a session on demand after a restart wiped its in-memory
     * registry — given a machine's proxy-auth username, return the same (proxyPassword,
     * accountProxy) pair registerSession pushes. 404 for an unknown/soft-deleted machine
     * (findByProxyUser is filtered by
     *
     * @SQLRestriction), so deleted machines are never resurrected. Secret-guarded, engine↔backend
     *   only.
     */
    @NoAuth
    @GetMapping("/internal/session")
    fun session(
        @RequestHeader(value = "X-Engine-Secret", required = false) secret: String?,
        @RequestParam("proxyUser") proxyUser: String,
    ): ResponseEntity<Map<String, String>> {
        checkSecret(secret)
        val machine =
            machineRepository.findByProxyUser(proxyUser) ?: return ResponseEntity.notFound().build()
        val password = machine.proxyPassword ?: return ResponseEntity.notFound().build()
        val accountProxy =
            machine.accountId?.let { accountRepository.findById(it).orElse(null)?.proxy }
                ?: config.engine.defaultAccountProxy
        return ResponseEntity.ok(mapOf("proxyPassword" to password, "accountProxy" to accountProxy))
    }
}
