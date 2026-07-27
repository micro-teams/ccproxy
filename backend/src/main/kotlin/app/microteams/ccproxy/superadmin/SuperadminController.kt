/*
 *  Description: The super-admin login endpoint: exchange the platform operator's password for a
 *               short-lived session JWT carrying the super-admin permission set.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.superadmin

import app.microteams.ccproxy.api.SuperadminApi
import app.microteams.ccproxy.authz.InvalidCredentialsError
import app.microteams.ccproxy.authz.ROLE_SUPER_ADMIN
import app.microteams.ccproxy.authz.TokenService
import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.model.SuperadminLoginRequestDTO
import app.microteams.ccproxy.model.SuperadminLoginResponseDTO
import java.security.MessageDigest
import org.rucca.cheese.auth.annotation.NoAuth
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class SuperadminController(
    private val config: CCProxyConfig,
    private val tokenService: TokenService,
) : SuperadminApi {
    @NoAuth
    override fun superadminLogin(
        superadminLoginRequestDTO: SuperadminLoginRequestDTO
    ): ResponseEntity<SuperadminLoginResponseDTO> {
        if (!constantTimeEquals(superadminLoginRequestDTO.password, config.superadminPassword)) {
            throw InvalidCredentialsError()
        }
        val minted = tokenService.mint(config.superadminId, ROLE_SUPER_ADMIN)
        return ResponseEntity.ok(
            SuperadminLoginResponseDTO(token = minted.token, expiresAt = minted.expiresAt)
        )
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
