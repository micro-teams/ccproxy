/*
 *  Description: The login-request work queue and its transitions. startForMachine() kicks off the
 *               async OAuth prep; submitLoginCode() applies the operator's real code; cancel() aborts
 *               an in-flight login. Maps to DTOs enriched with the machine + account context the
 *               operator needs (email / proxy / remark).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.loginrequest

import app.microteams.ccproxy.account.AccountRepository
import app.microteams.ccproxy.common.error.ConflictError
import app.microteams.ccproxy.common.helper.PageHelper
import app.microteams.ccproxy.credential.CredentialRepository
import app.microteams.ccproxy.credential.CredentialScope
import app.microteams.ccproxy.machine.MachineRepository
import app.microteams.ccproxy.machine.MachineStatus
import app.microteams.ccproxy.model.*
import java.security.SecureRandom
import java.time.ZoneOffset
import org.rucca.cheese.common.error.BadRequestError
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun LoginRequestStatus.toDTO() =
    when (this) {
        LoginRequestStatus.PREPARING -> LoginRequestStatusDTO.preparing
        LoginRequestStatus.AWAITING_CODE -> LoginRequestStatusDTO.awaitingCode
        LoginRequestStatus.APPLYING -> LoginRequestStatusDTO.applying
        LoginRequestStatus.COMPLETED -> LoginRequestStatusDTO.completed
        LoginRequestStatus.FAILED -> LoginRequestStatusDTO.failed
        LoginRequestStatus.EXPIRED -> LoginRequestStatusDTO.expired
        LoginRequestStatus.CANCELLED -> LoginRequestStatusDTO.cancelled
    }

fun LoginRequestStatusDTO.toEntity() =
    when (this) {
        LoginRequestStatusDTO.preparing -> LoginRequestStatus.PREPARING
        LoginRequestStatusDTO.awaitingCode -> LoginRequestStatus.AWAITING_CODE
        LoginRequestStatusDTO.applying -> LoginRequestStatus.APPLYING
        LoginRequestStatusDTO.completed -> LoginRequestStatus.COMPLETED
        LoginRequestStatusDTO.failed -> LoginRequestStatus.FAILED
        LoginRequestStatusDTO.expired -> LoginRequestStatus.EXPIRED
        LoginRequestStatusDTO.cancelled -> LoginRequestStatus.CANCELLED
    }

@Service
@Transactional
class LoginRequestService(
    private val repository: LoginRequestRepository,
    private val machineRepository: MachineRepository,
    private val accountRepository: AccountRepository,
    private val connectorOrchestrator: ConnectorLoginOrchestrator,
    private val hub: app.microteams.ccproxy.machine.link.MachineHub,
    private val credentialRepository: CredentialRepository,
) {
    private val rng = SecureRandom()

    fun get(id: IdType): LoginRequest =
        repository.findById(id).orElseThrow { NotFoundError("login_request", id) }

    /**
     * For the tenant "own-machine-login" predicate: a login-request's owner is its machine's
     * tenant.
     */
    fun getOwnerTenant(id: IdType): IdType = get(id).tenantId!!

    fun toDTO(r: LoginRequest): LoginRequestDTO {
        val machine = machineRepository.findById(r.machineId!!).orElse(null)
        val account = r.accountId?.let { accountRepository.findById(it).orElse(null) }
        return LoginRequestDTO(
            id = r.id!!,
            machineId = r.machineId!!,
            tenantId = r.tenantId!!,
            accountId = r.accountId ?: 0,
            accountEmail = account?.email ?: "",
            status = r.status.toDTO(),
            machineLabel = machine?.label,
            accountProxy = account?.proxy,
            accountRemark = account?.remark,
            oauthUrl = r.oauthUrl,
            error = r.error,
            createdAt = r.createdAt?.atOffset(ZoneOffset.UTC),
            expiresAt = r.expiresAt,
        )
    }

    fun startForMachine(machineId: IdType): LoginRequestDTO {
        val machine =
            machineRepository.findById(machineId).orElseThrow {
                NotFoundError("machine", machineId)
            }
        if (machine.status == MachineStatus.LOGGING_IN && machine.currentLoginRequestId != null) {
            throw ConflictError("a login for machine $machineId is already in progress")
        }
        // Bind a subscription account at LOGIN, not at birth — a machine that never switches to
        // official never consumes one. (The engine session is registered from these credentials in
        // the orchestrator, which needs the bound account's egress proxy.)
        if (machine.accountId == null) {
            val account =
                accountRepository.findFirstByStatusOrderByIdAsc(
                    app.microteams.ccproxy.account.AccountStatus.ACTIVE
                ) ?: throw ConflictError("no account available in the pool to bind")
            machine.accountId = account.id
            machineRepository.save(machine)
        }
        var req =
            LoginRequest(
                machineId = machine.id,
                tenantId = machine.tenantId,
                accountId = machine.accountId,
                status = LoginRequestStatus.PREPARING,
            )
        req = repository.save(req)
        machine.status = MachineStatus.LOGGING_IN
        machine.currentLoginRequestId = req.id
        machineRepository.save(machine)
        // Every machine drives login over its connector's dial-out link now. If the bound account
        // carries a setup-token, activate directly (no interactive /login); otherwise drive the
        // OAuth
        // flow through the operator.
        val hasSetupToken =
            credentialRepository
                .findByScopeAndCredKey(CredentialScope.ACCOUNT, machine.accountId.toString())
                ?.setupToken != null
        if (hasSetupToken) connectorOrchestrator.prepareViaSetupToken(req.id!!)
        else connectorOrchestrator.prepare(req.id!!)
        return toDTO(req)
    }

    fun submitCode(id: IdType, body: SubmitLoginCodeRequestDTO): LoginRequestDTO {
        val req = get(id)
        if (req.status != LoginRequestStatus.AWAITING_CODE) {
            throw ConflictError("login-request $id is not awaiting a code")
        }
        val (code, state) = parseCode(body)
        val fakeCode = randomHex(32)
        connectorOrchestrator.apply(req.id!!, code, state, fakeCode)
        req.status = LoginRequestStatus.APPLYING
        return toDTO(repository.save(req))
    }

    fun cancel(id: IdType): LoginRequestDTO {
        val req = get(id)
        if (
            req.status in
                setOf(
                    LoginRequestStatus.COMPLETED,
                    LoginRequestStatus.FAILED,
                    LoginRequestStatus.CANCELLED,
                )
        ) {
            return toDTO(req)
        }
        // tmuxSession holds the connector screen id of the in-flight login; tear that screen down.
        req.tmuxSession?.let { sid ->
            val machine = machineRepository.findById(req.machineId!!).orElse(null)
            if (machine != null) {
                runCatching { hub.closeScreen(machine.id.toString(), sid) }
                machine.status = MachineStatus.AWAITING_LOGIN
                machine.currentLoginRequestId = null
                machineRepository.save(machine)
            }
        }
        req.status = LoginRequestStatus.CANCELLED
        return toDTO(repository.save(req))
    }

    fun list(
        status: LoginRequestStatusDTO?,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<LoginRequestDTO>, PageDTO> {
        val filter = (status ?: LoginRequestStatusDTO.awaitingCode).toEntity()
        val all = repository.findByStatus(filter).sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { toDTO(it) } to info
    }

    private fun parseCode(body: SubmitLoginCodeRequestDTO): Pair<String, String?> {
        body.codeState
            ?.takeIf { it.isNotBlank() }
            ?.let {
                val parts = it.split("#", limit = 2)
                return parts[0] to parts.getOrNull(1)
            }
        val code = body.code?.takeIf { it.isNotBlank() } ?: throw BadRequestError("code required")
        return code to body.state
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
