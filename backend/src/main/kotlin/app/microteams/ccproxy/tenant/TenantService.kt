/*
 *  Description: CRUD for tenants and their auth secrets (delegated to the shared authz.SecretService,
 *               realm = TENANT). Secrets are returned in plaintext only once and stored only as a
 *               SHA-256 hash.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.tenant

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

fun Tenant.toDTO() =
    TenantDTO(
        id = this.id!!,
        name = this.name!!,
        status = this.status.toDTO(),
        createdAt = this.createdAt?.atOffset(ZoneOffset.UTC),
    )

fun TenantStatus.toDTO() =
    when (this) {
        TenantStatus.ACTIVE -> TenantStatusDTO.active
        TenantStatus.SUSPENDED -> TenantStatusDTO.suspended
    }

fun TenantStatusDTO.toEntity() =
    when (this) {
        TenantStatusDTO.active -> TenantStatus.ACTIVE
        TenantStatusDTO.suspended -> TenantStatus.SUSPENDED
    }

@Service
@Transactional
class TenantService(
    private val tenantRepository: TenantRepository,
    private val secretService: SecretService,
) {
    fun getTenant(id: IdType): Tenant =
        tenantRepository.findById(id).orElseThrow { NotFoundError("tenant", id) }

    fun getTenantDTO(id: IdType): TenantDTO = getTenant(id).toDTO()

    fun createTenant(name: String): TenantDTO = tenantRepository.save(Tenant(name = name)).toDTO()

    fun listTenants(pageStart: IdType?, pageSize: Int): Pair<List<TenantDTO>, PageDTO> {
        val all = tenantRepository.findAll().sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun updateTenant(id: IdType, name: String?, status: TenantStatusDTO?): TenantDTO {
        val tenant = getTenant(id)
        name?.let { tenant.name = it }
        status?.let { tenant.status = it.toEntity() }
        return tenantRepository.save(tenant).toDTO()
    }

    fun deleteTenant(id: IdType) {
        val tenant = getTenant(id)
        tenant.deletedAt = LocalDateTime.now()
        tenantRepository.save(tenant)
    }

    fun listSecrets(
        tenantId: IdType,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<AuthSecretDTO>, PageDTO> {
        getTenant(tenantId)
        val all = secretService.list(tenantId, AuthSecretRole.TENANT)
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun createSecret(tenantId: IdType, label: String?): CreateSecretResponseDTO {
        getTenant(tenantId)
        val (saved, plaintext) = secretService.create(tenantId, AuthSecretRole.TENANT, label)
        return CreateSecretResponseDTO(id = saved.id!!, secret = plaintext, label = label)
    }

    fun revokeSecret(tenantId: IdType, secretId: IdType) {
        getTenant(tenantId)
        secretService.revoke(tenantId, AuthSecretRole.TENANT, secretId)
    }
}
