/*
 *  Description: The machine module's controller. Tenants manage their own machines (scoped by the
 *               "owned" predicate); super-admin sees all and can rebind accounts. Registers the
 *               machine owner-id provider the authz layer uses for tenant scoping.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

import app.microteams.ccproxy.api.MachineApi
import app.microteams.ccproxy.loginrequest.LoginRequestService
import app.microteams.ccproxy.model.*
import app.microteams.ccproxy.usage.UsageService
import javax.annotation.PostConstruct
import org.rucca.cheese.auth.AuthenticationService
import org.rucca.cheese.auth.AuthorizationService
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.cheese.auth.annotation.ResourceId
import org.rucca.cheese.common.persistent.IdType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class MachineController(
    private val machineService: MachineService,
    private val loginRequestService: LoginRequestService,
    private val usageService: UsageService,
    private val authenticationService: AuthenticationService,
    private val authorizationService: AuthorizationService,
) : MachineApi {

    @PostConstruct
    fun initialize() {
        authorizationService.ownerIds.register("machine", machineService::getOwnerTenant)
    }

    /** Super-admins hold permissions on the "tenant" resource; tenants never do. */
    private fun isSuperAdmin(): Boolean =
        authorizationService.verify(authenticationService.getToken()).permissions.any {
            it.authorizedResource.types?.contains("tenant") == true
        }

    private fun callerTenantId(): IdType? =
        if (isSuperAdmin()) null else authenticationService.getCurrentUserId()

    @Guard("create-machine", "machine")
    override fun createMachine(
        @RequestBody createMachineRequestDTO: CreateMachineRequestDTO
    ): ResponseEntity<MachineDTO> {
        val created =
            machineService.createMachine(
                authenticationService.getCurrentUserId(),
                createMachineRequestDTO,
            )
        // For a connector-mode machine, hand back the exact one-line command to install + connect
        // it,
        // with the base derived from this request's origin (correct behind any proxy).
        val withCmd =
            if (created.connector == true && created.deviceToken != null) {
                val base = currentOrigin() + "/ccproxy"
                created.copy(
                    installCommand =
                        "curl -fsSL $base/install.sh | sh && " +
                            "ccproxy-connector connect $base --token ${created.deviceToken}"
                )
            } else {
                created
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(withCmd)
    }

    /**
     * The forwarded-header origin (scheme://host), the same derivation the rest of the backend
     * uses.
     */
    private fun currentOrigin(): String {
        val req =
            (org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()
                    as org.springframework.web.context.request.ServletRequestAttributes)
                .request
        val proto = req.getHeader("X-Forwarded-Proto") ?: req.scheme
        val host = req.getHeader("X-Forwarded-Host") ?: req.getHeader("Host")
        return "$proto://$host"
    }

    @Guard("list-machine", "machine")
    override fun listMachines(
        pageStart: Long?,
        pageSize: Int,
        status: MachineStatusDTO?,
    ): ResponseEntity<ListMachinesResponseDTO> {
        val superAdmin = isSuperAdmin()
        val (items, page) =
            machineService.listMachines(callerTenantId(), pageStart, pageSize, status)
        return ResponseEntity.ok(
            ListMachinesResponseDTO(
                items = items.map { machineService.toDTO(it, superAdmin) },
                page = page,
            )
        )
    }

    @Guard("get-machine", "machine")
    override fun getMachine(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<MachineDTO> =
        ResponseEntity.ok(machineService.toDTO(machineService.getMachine(id), isSuperAdmin()))

    @Guard("update-machine", "machine")
    override fun updateMachine(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updateMachineRequestDTO: UpdateMachineRequestDTO,
    ): ResponseEntity<MachineDTO> =
        ResponseEntity.ok(
            machineService.toDTO(
                machineService.updateMachine(id, updateMachineRequestDTO.label),
                isSuperAdmin(),
            )
        )

    @Guard("delete-machine", "machine")
    override fun deleteMachine(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<Unit> {
        machineService.deleteMachine(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }

    @Guard("rebind-machine-account", "machine")
    override fun rebindMachineAccount(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody rebindAccountRequestDTO: RebindAccountRequestDTO,
    ): ResponseEntity<MachineDTO> =
        ResponseEntity.ok(
            machineService.toDTO(
                machineService.rebindAccount(id, rebindAccountRequestDTO.accountId),
                isSuperAdmin(),
            )
        )

    @Guard("switch-machine-account", "machine")
    override fun switchMachineAccount(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody switchAccountRequestDTO: SwitchAccountRequestDTO,
    ): ResponseEntity<SwitchAccountResponseDTO> {
        val accountId = switchAccountRequestDTO.accountId
        // Rebind the pointer, then actually re-login so the live upstream credential switches
        // (rebind alone only flips machine.accountId). Detect the flow before starting: an account
        // with a setup-token activates automatically; otherwise an operator must finish /login.
        machineService.rebindAccount(id, accountId)
        val requiresManualLogin = !loginRequestService.accountHasSetupToken(accountId)
        val loginRequest = loginRequestService.startForMachine(id)
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(
                SwitchAccountResponseDTO(
                    requiresManualLogin = requiresManualLogin,
                    loginRequest = loginRequest,
                )
            )
    }

    @Guard("reprovision-machine", "machine")
    override fun reprovisionMachine(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<MachineDTO> =
        ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(machineService.toDTO(machineService.reprovision(id), isSuperAdmin()))

    @Guard("start-machine-login", "machine")
    override fun startMachineLogin(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<LoginRequestDTO> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(loginRequestService.startForMachine(id))

    @Guard("get-machine-usage", "machine")
    override fun getMachineUsage(
        @PathVariable("id") @ResourceId id: IdType,
        pageStart: Long?,
        pageSize: Int,
        since: Long?,
        until: Long?,
    ): ResponseEntity<ListUsageResponseDTO> {
        val (items, page) = usageService.listForMachine(id, pageStart, pageSize, since, until)
        return ResponseEntity.ok(ListUsageResponseDTO(items = items, page = page))
    }
}
