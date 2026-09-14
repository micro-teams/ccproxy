/*
 *  Description: Kotlin port of ccproxy_engine.py's report_usage/post_usage/report_ratelimit — since
 *               metering now runs IN this backend process, these call UsageService and
 *               AccountRateLimitRepository directly instead of POSTing to /internal/usage and
 *               /internal/ratelimit. Best-effort and isolated: a failure here must never affect the
 *               client-facing relay, matching Python's separate try/except around each report.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import app.microteams.ccproxy.account.AccountRateLimit
import app.microteams.ccproxy.account.AccountRateLimitRepository
import app.microteams.ccproxy.machine.MachineRepository
import app.microteams.ccproxy.usage.UsageService
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Scraped `anthropic-ratelimit-unified-*` headers off one response. */
data class RateLimitSnapshot(
    val fiveHUtilization: Double?,
    val fiveHResetAt: Long?,
    val fiveHStatus: String?,
    val sevenDUtilization: Double?,
    val sevenDResetAt: Long?,
    val sevenDStatus: String?,
)

@Component
class DataplaneReporting(
    private val usageService: UsageService,
    private val machineRepository: MachineRepository,
    private val accountRateLimitRepository: AccountRateLimitRepository,
) {
    private val log = LoggerFactory.getLogger(DataplaneReporting::class.java)

    fun reportUsage(
        proxyUser: String,
        model: String?,
        inputTokens: Long,
        outputTokens: Long,
        cacheReadTokens: Long,
        cacheWriteTokens: Long,
    ) {
        try {
            usageService.record(
                proxyUser,
                model ?: "unknown",
                inputTokens,
                outputTokens,
                cacheReadTokens,
                cacheWriteTokens,
            )
        } catch (e: Exception) {
            log.warn("usage report failed for $proxyUser: ${e.message}")
        }
    }

    @Transactional
    fun reportRateLimit(proxyUser: String, snapshot: RateLimitSnapshot) {
        try {
            val accountId = machineRepository.findByProxyUser(proxyUser)?.accountId ?: return
            val row =
                accountRateLimitRepository.findByAccountId(accountId)
                    ?: AccountRateLimit(accountId = accountId)
            row.fiveHUtilization = snapshot.fiveHUtilization
            row.fiveHResetAt = snapshot.fiveHResetAt
            row.fiveHStatus = snapshot.fiveHStatus
            row.sevenDUtilization = snapshot.sevenDUtilization
            row.sevenDResetAt = snapshot.sevenDResetAt
            row.sevenDStatus = snapshot.sevenDStatus
            row.observedAt = Instant.now()
            accountRateLimitRepository.save(row)
        } catch (e: Exception) {
            log.warn("ratelimit report failed for $proxyUser: ${e.message}")
        }
    }

    companion object {
        private val UNIFIED_PREFIX = "anthropic-ratelimit-unified-"

        /**
         * Extract the unified 5h/7d quota snapshot from a response's headers, or null if absent.
         */
        fun extractRateLimit(headers: Map<String, String>): RateLimitSnapshot? {
            val h = HashMap<String, String>()
            for ((k, v) in headers) {
                val kl = k.lowercase()
                if (kl.startsWith(UNIFIED_PREFIX)) h[kl] = v
            }
            if (h.isEmpty()) return null
            return RateLimitSnapshot(
                fiveHUtilization = h["${UNIFIED_PREFIX}5h-utilization"]?.toDoubleOrNull(),
                fiveHResetAt = h["${UNIFIED_PREFIX}5h-reset"]?.toLongOrNull(),
                fiveHStatus = h["${UNIFIED_PREFIX}5h-status"],
                sevenDUtilization = h["${UNIFIED_PREFIX}7d-utilization"]?.toDoubleOrNull(),
                sevenDResetAt = h["${UNIFIED_PREFIX}7d-reset"]?.toLongOrNull(),
                sevenDStatus = h["${UNIFIED_PREFIX}7d-status"],
            )
        }
    }
}
