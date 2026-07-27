/*
 *  Description: Mints session JWTs for the two auth realms (super-admin, tenant). The token carries
 *               the role's permission matrix in the `payload` claim, byte-compatible with what the
 *               borrowed org.rucca.cheese.auth verifier expects (same HMAC256 secret, same
 *               TokenPayload shape) — so signing lives here while the kernel stays verify-only.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.authz

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import org.rucca.cheese.auth.TokenPayload
import org.rucca.cheese.common.config.ApplicationConfig
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service

data class MintedToken(val token: String, val expiresAt: Long)

@Service
class TokenService(
    applicationConfig: ApplicationConfig,
    objectMapper: ObjectMapper,
    private val rolePermissionService: RolePermissionService,
) {
    private val jwtSecret = applicationConfig.jwtSecret
    private val claimMapper =
        objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL)

    fun mint(userId: IdType, role: String, ttlMs: Long = DEFAULT_TTL_MS): MintedToken {
        val now = System.currentTimeMillis()
        val validUntil = now + ttlMs
        val authorization = rolePermissionService.getAuthorizationForUserWithRole(userId, role)
        val payload = TokenPayload(authorization, signedAt = now, validUntil = validUntil)
        @Suppress("UNCHECKED_CAST")
        val claim = claimMapper.convertValue(payload, Map::class.java) as Map<String, Any>
        val token = JWT.create().withClaim("payload", claim).sign(Algorithm.HMAC256(jwtSecret))
        return MintedToken(token = token, expiresAt = validUntil / 1000)
    }

    private companion object {
        const val DEFAULT_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
