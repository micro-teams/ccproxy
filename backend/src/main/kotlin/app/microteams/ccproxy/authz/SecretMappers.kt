/*
 *  Description: DTO mappers for opaque secrets, shared by the tenant and login-operator modules.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.authz

import app.microteams.ccproxy.model.AuthSecretDTO
import app.microteams.ccproxy.model.AuthSecretStatusDTO
import java.time.ZoneOffset

fun AuthSecret.toDTO() =
    AuthSecretDTO(
        id = this.id!!,
        ownerId = this.ownerId!!,
        label = this.label,
        status =
            when (this.status) {
                AuthSecretStatus.ACTIVE -> AuthSecretStatusDTO.active
                AuthSecretStatus.REVOKED -> AuthSecretStatusDTO.revoked
            },
        createdAt = this.createdAt?.atOffset(ZoneOffset.UTC),
        lastUsedAt = null,
    )
