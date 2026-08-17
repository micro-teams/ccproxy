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
import app.microteams.ccproxy.common.error.EngineUnavailableError
import app.microteams.ccproxy.common.helper.PageHelper
import app.microteams.ccproxy.credential.CredentialRepository
import app.microteams.ccproxy.credential.CredentialScope
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
    private val credentialRepository: CredentialRepository,
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

    fun toDTO(m: Machine, includeAccount: Boolean, includeSecrets: Boolean = false): MachineDTO =
        m.toDTO(
            includeAccount = includeAccount,
            accountEmail = accountEmail(m),
            online = m.id?.let { hub.isOnline(it.toString()) } ?: false,
            includeSecrets = includeSecrets,
        )

    fun createMachine(tenantId: IdType, req: CreateMachineRequestDTO): MachineDTO {
        // Birth init: NO account is bound here. MicroCloud calls this at VM creation for every
        // machine (most of which stay on newapi forever); binding a subscription account at birth
        // would exhaust the pool. provision() only points Claude at the engine — which tunnels this
        // still-unregistered session straight through — so no account is consumed until login.
        // Every machine is connector-driven. It gets a durable device token (its only credential,
        // presented at the WebSocket handshake). A machine WITH an SSH host is bootstrapped —
        // provision() SSHes in ONCE to install the connector + dial in; a machine with NO host
        // waits
        // for the operator to run the install command by hand. Config + login then go over the
        // connector; the backend never SSH-drives a machine again.
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
                deviceToken = randomToken(24),
            )
        // Flush the INSERT so the id is assigned AND @CreationTimestamp fires; then the follow-up
        // update (embedding the id in the proxy username so the engine keys sessions stably) is a
        // real UPDATE with created_at already set.
        machine = machineRepository.saveAndFlush(machine)
        machine.proxyUser = "m${machine.id}"
        machine = machineRepository.save(machine)

        // No-ops for a hostless (connector) machine; SSH-bootstraps a host machine onto the
        // connector.
        provisioner.provision(machine.id!!)
        // The create response is the one place the device token + install command are returned.
        return toDTO(machine, includeAccount = false, includeSecrets = true)
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

    /**
     * Delete = revoke this machine's ticket, verifiably. A 204 must MEAN the credential can never
     * spend again, so the engine drop is not best-effort: if the engine cannot confirm removing the
     * live session, we fail the whole call (502, transaction rolled back, nothing deleted) instead
     * of returning success while the session keeps serving. The durable SESSION credential row is
     * soft-deleted in the same transaction — the engine reloads every row at startup, so a row left
     * behind would resurrect the revoked ticket (proxy auth + real tokens) on the next engine
     * restart. Everything is keyed by this machine's proxyUser; no other machine is touched.
     */
    fun deleteMachine(id: IdType) {
        val m = getMachine(id)
        try {
            engineClient.removeSession(m.proxyUser!!)
        } catch (e: Exception) {
            throw EngineUnavailableError(
                "proxy-engine did not confirm dropping session ${m.proxyUser}: ${e.message} — " +
                    "the credential may still be live; machine $id was NOT deleted, retry"
            )
        }
        val now = LocalDateTime.now()
        credentialRepository.findByScopeAndCredKey(CredentialScope.SESSION, m.proxyUser!!)?.let {
            it.deletedAt = now
            credentialRepository.save(it)
        }
        m.deletedAt = now
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
