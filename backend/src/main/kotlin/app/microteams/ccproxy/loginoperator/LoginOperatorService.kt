/*
 *  Description: CRUD for login-operators and their auth secrets (realm = LOGIN_OPERATOR).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.loginoperator

import app.microteams.ccproxy.authz.AuthSecretRole
import app.microteams.ccproxy.authz.SecretService
import app.microteams.ccproxy.authz.toDTO
import app.microteams.ccproxy.common.helper.PageHelper
import app.microteams.ccproxy.model.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun LoginOperator.toDTO() =
    LoginOperatorDTO(
        id = this.id!!,
        name = this.name!!,
        status =
            when (this.status) {
                LoginOperatorStatus.ACTIVE -> LoginOperatorStatusDTO.active
                LoginOperatorStatus.SUSPENDED -> LoginOperatorStatusDTO.suspended
            },
        createdAt = this.createdAt?.atOffset(ZoneOffset.UTC),
    )

fun LoginOperatorStatusDTO.toEntity() =
    when (this) {
        LoginOperatorStatusDTO.active -> LoginOperatorStatus.ACTIVE
        LoginOperatorStatusDTO.suspended -> LoginOperatorStatus.SUSPENDED
    }

@Service
@Transactional
class LoginOperatorService(
    private val repository: LoginOperatorRepository,
    private val secretService: SecretService,
) {
    fun get(id: IdType): LoginOperator =
        repository.findById(id).orElseThrow { NotFoundError("login_operator", id) }

    fun getDTO(id: IdType): LoginOperatorDTO = get(id).toDTO()

    fun create(name: String): LoginOperatorDTO = repository.save(LoginOperator(name = name)).toDTO()

    fun list(pageStart: IdType?, pageSize: Int): Pair<List<LoginOperatorDTO>, PageDTO> {
        val all = repository.findAll().sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun update(id: IdType, name: String?, status: LoginOperatorStatusDTO?): LoginOperatorDTO {
        val op = get(id)
        name?.let { op.name = it }
        status?.let { op.status = it.toEntity() }
        return repository.save(op).toDTO()
    }

    fun delete(id: IdType) {
        val op = get(id)
        op.deletedAt = LocalDateTime.now()
        repository.save(op)
    }

    fun listSecrets(
        id: IdType,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<AuthSecretDTO>, PageDTO> {
        get(id)
        val all = secretService.list(id, AuthSecretRole.LOGIN_OPERATOR)
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun createSecret(id: IdType, label: String?): CreateSecretResponseDTO {
        get(id)
        val (saved, plaintext) = secretService.create(id, AuthSecretRole.LOGIN_OPERATOR, label)
        return CreateSecretResponseDTO(id = saved.id!!, secret = plaintext, label = label)
    }

    fun revokeSecret(id: IdType, secretId: IdType) {
        get(id)
        secretService.revoke(id, AuthSecretRole.LOGIN_OPERATOR, secretId)
    }
}
