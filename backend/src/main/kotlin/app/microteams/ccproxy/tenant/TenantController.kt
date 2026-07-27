/*
 *  Description: The tenant module's controller — super-admin management of tenants and their auth
 *               secrets. Every method implements a TenantApi operation; authorization is a @Guard
 *               against the super-admin permission set.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.tenant

import app.microteams.ccproxy.api.TenantApi
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
class TenantController(private val tenantService: TenantService) : TenantApi {

    @Guard("list-tenants", "tenant")
    override fun listTenants(
        pageStart: Long?,
        pageSize: Int,
    ): ResponseEntity<ListTenantsResponseDTO> {
        val (items, page) = tenantService.listTenants(pageStart, pageSize)
        return ResponseEntity.ok(ListTenantsResponseDTO(items = items, page = page))
    }

    @Guard("create-tenant", "tenant")
    override fun createTenant(
        @RequestBody createTenantRequestDTO: CreateTenantRequestDTO
    ): ResponseEntity<TenantDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(tenantService.createTenant(createTenantRequestDTO.name))

    @Guard("get-tenant", "tenant")
    override fun getTenant(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<TenantDTO> =
        ResponseEntity.ok(tenantService.getTenantDTO(id))

    @Guard("update-tenant", "tenant")
    override fun updateTenant(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updateTenantRequestDTO: UpdateTenantRequestDTO,
    ): ResponseEntity<TenantDTO> =
        ResponseEntity.ok(
            tenantService.updateTenant(
                id,
                updateTenantRequestDTO.name,
                updateTenantRequestDTO.status,
            )
        )

    @Guard("delete-tenant", "tenant")
    override fun deleteTenant(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<Unit> {
        tenantService.deleteTenant(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }

    @Guard("list-tenant-secrets", "tenant")
    override fun listTenantSecrets(
        @PathVariable("id") @ResourceId id: IdType,
        pageStart: Long?,
        pageSize: Int,
    ): ResponseEntity<ListAuthSecretsResponseDTO> {
        val (items, page) = tenantService.listSecrets(id, pageStart, pageSize)
        return ResponseEntity.ok(ListAuthSecretsResponseDTO(items = items, page = page))
    }

    @Guard("create-tenant-secret", "tenant")
    override fun createTenantSecret(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody createSecretRequestDTO: CreateSecretRequestDTO,
    ): ResponseEntity<CreateSecretResponseDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(tenantService.createSecret(id, createSecretRequestDTO.label))

    @Guard("revoke-tenant-secret", "tenant")
    override fun revokeTenantSecret(
        @PathVariable("id") @ResourceId id: IdType,
        @PathVariable("secretId") secretId: Long,
    ): ResponseEntity<Unit> {
        tenantService.revokeSecret(id, secretId)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }
}
