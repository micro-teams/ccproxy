/*
 *  Description: Per-account rate-limit snapshot — the latest 5h / 7d unified quota utilization and
 *               reset the proxy-engine scraped from Anthropic's `anthropic-ratelimit-unified-*`
 *               response headers. One row per account, last-wins upsert. Read-only observability;
 *               no auto-switch decisions are made from it.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.account

import jakarta.persistence.*
import java.time.Instant
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "account_rate_limit", indexes = [Index(columnList = "accountId", unique = true)])
class AccountRateLimit(
    @Column(nullable = false, unique = true) var accountId: IdType? = null,
    /** 0..1 fraction of the 5h rolling window used, quantized to 0.01; null if never seen. */
    @Column var fiveHUtilization: Double? = null,
    /** Epoch seconds when the 5h window resets. */
    @Column var fiveHResetAt: Long? = null,
    @Column var fiveHStatus: String? = null,
    /** 0..1 fraction of the 7d weekly window used. */
    @Column var sevenDUtilization: Double? = null,
    @Column var sevenDResetAt: Long? = null,
    @Column var sevenDStatus: String? = null,
    /** When the engine last scraped these headers (server clock). */
    @Column var observedAt: Instant? = null,
) : BaseEntity()

interface AccountRateLimitRepository : JpaRepository<AccountRateLimit, IdType> {
    fun findByAccountId(accountId: IdType): AccountRateLimit?
}
