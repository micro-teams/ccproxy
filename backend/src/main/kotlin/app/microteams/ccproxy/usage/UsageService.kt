/*
 *  Description: Records usage reported by the proxy-engine and serves it back as raw entries or as
 *               fixed-width time buckets. Enforces no quota — that is MicroCloud's job.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.usage

import app.microteams.ccproxy.common.helper.PageHelper
import app.microteams.ccproxy.machine.MachineRepository
import app.microteams.ccproxy.model.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun UsageEntry.toDTO() =
    UsageEntryDTO(
        id = this.id!!,
        tenantId = this.tenantId!!,
        machineId = this.machineId!!,
        model = this.model!!,
        inputTokens = this.inputTokens,
        outputTokens = this.outputTokens,
        at = this.at.atOffset(ZoneOffset.UTC),
        cacheReadTokens = this.cacheReadTokens,
        cacheWriteTokens = this.cacheWriteTokens,
    )

@Service
@Transactional
class UsageService(
    private val repository: UsageEntryRepository,
    private val machineRepository: MachineRepository,
) {
    /**
     * Called from the internal engine-ingest endpoint. Resolves the machine by its proxy-auth user.
     */
    fun record(
        proxyUser: String,
        model: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheReadTokens: Long?,
        cacheWriteTokens: Long?,
    ) {
        val machine = machineRepository.findByProxyUser(proxyUser) ?: return
        repository.save(
            UsageEntry(
                tenantId = machine.tenantId,
                machineId = machine.id,
                accountId = machine.accountId,
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
            )
        )
        machine.lastUsedAt = LocalDateTime.now()
        machineRepository.save(machine)
    }

    fun listForMachine(
        machineId: IdType,
        pageStart: IdType?,
        pageSize: Int,
        since: Long?,
        until: Long?,
    ): Pair<List<UsageEntryDTO>, PageDTO> {
        val all =
            repository
                .findByMachineId(machineId)
                .filter { inWindow(it, since, until) }
                .sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun list(
        callerTenantId: IdType?,
        tenantId: IdType?,
        machineId: IdType?,
        pageStart: IdType?,
        pageSize: Int,
        since: Long?,
        until: Long?,
    ): Pair<List<UsageEntryDTO>, PageDTO> {
        val scopeTenant = callerTenantId ?: tenantId
        val base =
            when {
                machineId != null -> repository.findByMachineId(machineId)
                scopeTenant != null -> repository.findByTenantId(scopeTenant)
                else -> repository.findAll()
            }
        val all = base.filter { inWindow(it, since, until) }.sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun summary(
        callerTenantId: IdType?,
        tenantId: IdType?,
        machineId: IdType?,
        since: Long?,
        until: Long?,
        bucketSeconds: Int,
    ): UsageSummaryDTO {
        val bucket = if (bucketSeconds <= 0) 60L else bucketSeconds.toLong()
        val nowSec = Instant.now().epochSecond
        val from = since ?: (nowSec - 3600)
        val to = until ?: nowSec
        val scopeTenant = callerTenantId ?: tenantId
        val base =
            when {
                machineId != null -> repository.findByMachineId(machineId)
                scopeTenant != null -> repository.findByTenantId(scopeTenant)
                else -> repository.findAll()
            }
        val entries = base.filter { epoch(it) in from until to }
        val grouped = entries.groupBy { (epoch(it) / bucket) * bucket }
        val buckets =
            grouped.toSortedMap().map { (at, es) ->
                UsageBucketDTO(
                    at = at,
                    requests = es.size.toLong(),
                    inputTokens = es.sumOf { it.inputTokens },
                    outputTokens = es.sumOf { it.outputTokens },
                    cacheReadTokens = es.sumOf { it.cacheReadTokens ?: 0 },
                    cacheWriteTokens = es.sumOf { it.cacheWriteTokens ?: 0 },
                )
            }
        return UsageSummaryDTO(
            since = from,
            until = to,
            bucketSeconds = bucket.toInt(),
            buckets = buckets,
        )
    }

    private fun epoch(e: UsageEntry): Long = e.at.toEpochSecond(ZoneOffset.UTC)

    private fun inWindow(e: UsageEntry, since: Long?, until: Long?): Boolean {
        val t = epoch(e)
        if (since != null && t < since) return false
        if (until != null && t >= until) return false
        return true
    }
}
