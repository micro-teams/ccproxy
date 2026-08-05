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
    includeAccount: Boolean = false,
    accountEmail: String? = null,
    online: Boolean = false,
    installCommand: String? = null,
    // The device token is a machine-auth credential (it authenticates the WebSocket dial-in), so it
    // is returned ONLY on the create response the owning tenant needs to connect the machine —
    // never
    // in a list/get, the same "shown once" rule as the tenant/operator secrets.
    includeSecrets: Boolean = false,
): MachineDTO =
    MachineDTO(
        id = this.id!!,
        tenantId = this.tenantId!!,
        // A connector-mode machine has no host; SSH mode always does.
        connector = this.host.isNullOrBlank(),
        online = online,
        host = this.host,
        status = this.status.toDTO(),
        caCertInstalled = this.caCertInstalled,
        hasCredential = this.hasCredential,
        label = this.label,
        sshUser = this.sshUser,
        sshPort = this.sshPort,
        error = this.error,
        credentialExpiresAt = this.credentialExpiresAt,
        currentLoginRequestId = this.currentLoginRequestId,
        lastUsedAt = this.lastUsedAt?.atOffset(ZoneOffset.UTC),
        createdAt = this.createdAt?.atOffset(ZoneOffset.UTC),
        deviceToken = if (includeSecrets) this.deviceToken else null,
        installCommand = if (includeSecrets) installCommand else null,
        boundAccountId = if (includeAccount) this.accountId else null,
        boundAccountEmail = if (includeAccount) accountEmail else null,
    )
