/*
 *  Description: Machine lifecycle from the tenant's side: register (auto-bind an account + kick off
 *               async provisioning), list/get/update/delete, super-admin rebind, and the owner
 *               lookup the authz layer uses to scope a tenant to its own machines.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

import app.microteams.ccproxy.account.AccountRepository
import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.common.helper.PageHelper
import app.microteams.ccproxy.model.*
import java.security.SecureRandom
import java.time.LocalDateTime
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class MachineService(
    private val machineRepository: MachineRepository,
    private val accountRepository: AccountRepository,
    private val provisioner: MachineProvisioner,
    private val engineClient: EngineClient,
    private val config: CCProxyConfig,
    private val hub: app.microteams.ccproxy.machine.link.MachineHub,
) {
    private val rng = SecureRandom()

    /** Resolve a device token presented at the WebSocket handshake to its machine, or null. */
    fun verifyDeviceToken(token: String): Machine? =
        if (token.isBlank()) null else machineRepository.findByDeviceToken(token)

    fun getMachine(id: IdType): Machine =
        machineRepository.findById(id).orElseThrow { NotFoundError("machine", id) }

    /** For the authz "owned" predicate: a machine's owner is its tenant. */
    fun getOwnerTenant(id: IdType): IdType = getMachine(id).tenantId!!

    private fun accountEmail(m: Machine): String? =
        m.accountId?.let { accountRepository.findById(it).orElse(null)?.email }

    fun toDTO(m: Machine, includeAccount: Boolean): MachineDTO =
        m.toDTO(
            config.engine.proxyEndpoint,
            includeAccount,
            accountEmail(m),
            online = m.id?.let { hub.isOnline(it.toString()) } ?: false,
        )

    fun createMachine(tenantId: IdType, req: CreateMachineRequestDTO): MachineDTO {
        // Birth init: NO account is bound here. MicroCloud calls this at VM creation for every
        // machine (most of which stay on newapi forever); binding a subscription account at birth
        // would exhaust the pool. provision() only points Claude at the engine — which tunnels this
        // still-unregistered session straight through — so no account is consumed until login.
        // Connector mode: no SSH target given. The machine dials OUT via its connector, so instead
        // of
        // SSHing in we issue a durable device token it presents at the WebSocket handshake, and
        // skip
        // provisioning — the connector applies config and drives login once it is online.
        val connectorMode = req.host.isNullOrBlank()
        var machine =
            Machine(
                tenantId = tenantId,
                accountId = null,
                host = req.host?.takeIf { it.isNotBlank() },
                sshUser = req.sshUser?.takeIf { it.isNotBlank() } ?: "root",
                sshPort = req.sshPort ?: 22,
                label = req.label,
                status = MachineStatus.CREATED,
                proxyUser = "pending",
                proxyPassword = randomToken(24),
                deviceToken = if (connectorMode) randomToken(24) else null,
            )
        // Flush the INSERT so the id is assigned AND @CreationTimestamp fires; then the follow-up
        // update (embedding the id in the proxy username so the engine keys sessions stably) is a
        // real UPDATE with created_at already set.
        machine = machineRepository.saveAndFlush(machine)
        machine.proxyUser = "m${machine.id}"
        machine = machineRepository.save(machine)

        if (!connectorMode) provisioner.provision(machine.id!!)
        return toDTO(machine, includeAccount = false)
    }

    fun listMachines(
        callerTenantId: IdType?,
        pageStart: IdType?,
        pageSize: Int,
        status: MachineStatusDTO?,
    ): Pair<List<Machine>, PageDTO> {
        val all =
            (if (callerTenantId != null) machineRepository.findByTenantId(callerTenantId)
                else machineRepository.findAll())
                .filter { status == null || it.status.toDTO() == status }
                .sortedBy { it.id }
        return PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
    }

    fun updateMachine(id: IdType, label: String?): Machine {
        val m = getMachine(id)
        label?.let { m.label = it }
        return machineRepository.save(m)
    }

    fun deleteMachine(id: IdType) {
        val m = getMachine(id)
        runCatching { engineClient.removeSession(m.proxyUser!!) }
        m.deletedAt = LocalDateTime.now()
        machineRepository.save(m)
    }

    fun rebindAccount(id: IdType, accountId: IdType): Machine {
        val m = getMachine(id)
        accountRepository.findById(accountId).orElseThrow { NotFoundError("account", accountId) }
        m.accountId = accountId
        return machineRepository.save(m)
    }

    fun reprovision(id: IdType): Machine {
        val m = getMachine(id)
        provisioner.provision(m.id!!)
        return m
    }

    private fun randomToken(len: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
    }
}
