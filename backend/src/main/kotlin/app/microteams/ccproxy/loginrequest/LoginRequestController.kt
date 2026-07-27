/*
 *  Description: The login-operator's work-queue controller. Login-operators (and super-admin) list
 *               and act on login-requests; a tenant may cancel one for its own machine (scoped by
 *               the "owned" predicate on the login-request owner = its tenant).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.loginrequest

import app.microteams.ccproxy.api.LoginRequestApi
import app.microteams.ccproxy.model.*
import javax.annotation.PostConstruct
import org.rucca.cheese.auth.AuthorizationService
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.cheese.auth.annotation.ResourceId
import org.rucca.cheese.common.persistent.IdType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class LoginRequestController(
    private val service: LoginRequestService,
    private val authorizationService: AuthorizationService,
) : LoginRequestApi {

    @PostConstruct
    fun initialize() {
        authorizationService.ownerIds.register("login-request", service::getOwnerTenant)
    }

    @Guard("list-login-requests", "login-request")
    override fun listLoginRequests(
        pageStart: Long?,
        pageSize: Int,
        status: LoginRequestStatusDTO?,
    ): ResponseEntity<ListLoginRequestsResponseDTO> {
        val (items, page) = service.list(status, pageStart, pageSize)
        return ResponseEntity.ok(ListLoginRequestsResponseDTO(items = items, page = page))
    }

    @Guard("get-login-request", "login-request")
    override fun getLoginRequest(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<LoginRequestDTO> = ResponseEntity.ok(service.toDTO(service.get(id)))

    @Guard("submit-login-code", "login-request")
    override fun submitLoginCode(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody submitLoginCodeRequestDTO: SubmitLoginCodeRequestDTO,
    ): ResponseEntity<LoginRequestDTO> =
        ResponseEntity.ok(service.submitCode(id, submitLoginCodeRequestDTO))

    @Guard("cancel-login-request", "login-request")
    override fun cancelLoginRequest(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<LoginRequestDTO> = ResponseEntity.ok(service.cancel(id))
}
