/*
 *  Description: One metered Anthropic API call, reported by the proxy-engine. The billing primitive
 *               CCProxy exposes; MicroCloud aggregates it for its own billing/quota.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.usage

import jakarta.persistence.*
import java.time.LocalDateTime
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(
    name = "usage_entry",
    indexes =
        [
            Index(columnList = "tenant_id"),
            Index(columnList = "machine_id"),
            Index(columnList = "created_at"),
        ],
)
class UsageEntry(
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(name = "machine_id", nullable = false) var machineId: IdType? = null,
    @Column(name = "account_id") var accountId: IdType? = null,
    @Column(nullable = false) var model: String? = null,
    @Column(name = "input_tokens", nullable = false) var inputTokens: Long = 0,
    @Column(name = "output_tokens", nullable = false) var outputTokens: Long = 0,
    @Column(name = "cache_read_tokens") var cacheReadTokens: Long? = null,
    @Column(name = "cache_write_tokens") var cacheWriteTokens: Long? = null,
    @Column(name = "at", nullable = false) var at: LocalDateTime = LocalDateTime.now(),
) : BaseEntity()

interface UsageEntryRepository : JpaRepository<UsageEntry, IdType> {
    fun findByMachineId(machineId: IdType): List<UsageEntry>

    fun findByTenantId(tenantId: IdType): List<UsageEntry>
}
