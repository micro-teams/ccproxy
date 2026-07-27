/*
 *  Description: Super-admin management of login-operators and their auth secrets.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.loginoperator

import app.microteams.ccproxy.api.LoginOperatorApi
import app.microteams.ccproxy.model.*
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.cheese.auth.annotation.ResourceId
import org.rucca.cheese.common.persistent.IdType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class LoginOperatorController(private val service: LoginOperatorService) : LoginOperatorApi {

    @Guard("list-login-operators", "login-operator")
    override fun listLoginOperators(
        pageStart: Long?,
        pageSize: Int,
    ): ResponseEntity<ListLoginOperatorsResponseDTO> {
        val (items, page) = service.list(pageStart, pageSize)
        return ResponseEntity.ok(ListLoginOperatorsResponseDTO(items = items, page = page))
    }

    @Guard("create-login-operator", "login-operator")
    override fun createLoginOperator(
        @RequestBody createLoginOperatorRequestDTO: CreateLoginOperatorRequestDTO
    ): ResponseEntity<LoginOperatorDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(service.create(createLoginOperatorRequestDTO.name))

    @Guard("get-login-operator", "login-operator")
    override fun getLoginOperator(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<LoginOperatorDTO> = ResponseEntity.ok(service.getDTO(id))

    @Guard("update-login-operator", "login-operator")
    override fun updateLoginOperator(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updateLoginOperatorRequestDTO: UpdateLoginOperatorRequestDTO,
    ): ResponseEntity<LoginOperatorDTO> =
        ResponseEntity.ok(
            service.update(
                id,
                updateLoginOperatorRequestDTO.name,
                updateLoginOperatorRequestDTO.status,
            )
        )

    @Guard("delete-login-operator", "login-operator")
    override fun deleteLoginOperator(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<Unit> {
        service.delete(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }

    @Guard("list-login-operator-secrets", "login-operator")
    override fun listLoginOperatorSecrets(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<ListAuthSecretsResponseDTO> {
        val (items, page) = service.listSecrets(id, null, 100)
        return ResponseEntity.ok(ListAuthSecretsResponseDTO(items = items, page = page))
    }

    @Guard("create-login-operator-secret", "login-operator")
    override fun createLoginOperatorSecret(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody createSecretRequestDTO: CreateSecretRequestDTO,
    ): ResponseEntity<CreateSecretResponseDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createSecret(id, createSecretRequestDTO.label))

    @Guard("revoke-login-operator-secret", "login-operator")
    override fun revokeLoginOperatorSecret(
        @PathVariable("id") @ResourceId id: IdType,
        @PathVariable("secretId") secretId: Long,
    ): ResponseEntity<Unit> {
        service.revokeSecret(id, secretId)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }
}
