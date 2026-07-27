/*
 *  Description: Usage reads. A tenant is scoped to its own usage; super-admin sees all and may scope
 *               by tenantId.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.usage

import app.microteams.ccproxy.api.UsageApi
import app.microteams.ccproxy.model.*
import org.rucca.cheese.auth.AuthenticationService
import org.rucca.cheese.auth.AuthorizationService
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.cheese.common.persistent.IdType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class UsageController(
    private val usageService: UsageService,
    private val authenticationService: AuthenticationService,
    private val authorizationService: AuthorizationService,
) : UsageApi {

    private fun isSuperAdmin(): Boolean =
        authorizationService.verify(authenticationService.getToken()).permissions.any {
            it.authorizedResource.types?.contains("tenant") == true
        }

    private fun callerTenantId(): IdType? =
        if (isSuperAdmin()) null else authenticationService.getCurrentUserId()

    @Guard("list-usage", "usage")
    override fun listUsage(
        pageStart: Long?,
        pageSize: Int,
        since: Long?,
        until: Long?,
        tenantId: Long?,
        machineId: Long?,
    ): ResponseEntity<ListUsageResponseDTO> {
        val (items, page) =
            usageService.list(
                callerTenantId(),
                tenantId,
                machineId,
                pageStart,
                pageSize,
                since,
                until,
            )
        return ResponseEntity.ok(ListUsageResponseDTO(items = items, page = page))
    }

    @Guard("get-usage-summary", "usage")
    override fun getUsageSummary(
        since: Long?,
        until: Long?,
        bucket: Int,
        machineId: Long?,
        tenantId: Long?,
    ): ResponseEntity<UsageSummaryDTO> =
        ResponseEntity.ok(
            usageService.summary(callerTenantId(), tenantId, machineId, since, until, bucket)
        )
}
