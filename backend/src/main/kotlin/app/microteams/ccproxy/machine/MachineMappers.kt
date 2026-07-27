/*
 *  Description: Machine → DTO mapping. The bound-account fields are internal and populated only for
 *               super-admin callers; the proxy URL is assembled from the per-machine proxy-auth.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

import app.microteams.ccproxy.model.MachineDTO
import app.microteams.ccproxy.model.MachineStatusDTO
import java.time.ZoneOffset

fun MachineStatus.toDTO() =
    when (this) {
        MachineStatus.CREATED -> MachineStatusDTO.created
        MachineStatus.PROVISIONING -> MachineStatusDTO.provisioning
        MachineStatus.AWAITING_LOGIN -> MachineStatusDTO.awaitingLogin
        MachineStatus.LOGGING_IN -> MachineStatusDTO.loggingIn
        MachineStatus.READY -> MachineStatusDTO.ready
        MachineStatus.ERROR -> MachineStatusDTO.error
    }

fun Machine.httpsProxyUrl(endpoint: String): String = "http://$proxyUser:$proxyPassword@$endpoint"

fun Machine.toDTO(
    proxyEndpoint: String,
    includeAccount: Boolean = false,
    accountEmail: String? = null,
): MachineDTO =
    MachineDTO(
        id = this.id!!,
        tenantId = this.tenantId!!,
        host = this.host!!,
        status = this.status.toDTO(),
        caCertInstalled = this.caCertInstalled,
        hasCredential = this.hasCredential,
        label = this.label,
        sshUser = this.sshUser,
        sshPort = this.sshPort,
        error = this.error,
        httpsProxyUrl = this.httpsProxyUrl(proxyEndpoint),
        credentialExpiresAt = this.credentialExpiresAt,
        currentLoginRequestId = this.currentLoginRequestId,
        lastUsedAt = this.lastUsedAt?.atOffset(ZoneOffset.UTC),
        createdAt = this.createdAt?.atOffset(ZoneOffset.UTC),
        boundAccountId = if (includeAccount) this.accountId else null,
        boundAccountEmail = if (includeAccount) accountEmail else null,
    )
