/*
 *  Description: Resolves an opaque auth secret (tenant OR login-operator) into a session, per
 *               request. The caller sends its secret as `Authorization: Bearer <secret>`; this
 *               filter matches its SHA-256 hash, reads the role stored on the secret, audits the
 *               call, mints a short-lived JWT for that (owner, role), and rewrites the header so the
 *               borrowed org.rucca.cheese.auth verifier downstream sees an ordinary role token.
 *               Super-admin JWTs contain dots and are passed through untouched.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.authz

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.util.Collections
import java.util.Enumeration
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class SecretAuthFilter(
    private val authSecretRepository: AuthSecretRepository,
    private val auditLogRepository: AuditLogRepository,
    private val tokenService: TokenService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val raw = bearer(request.getHeader("Authorization"))
        // JWTs (super-admin sessions) have dots; an opaque secret does not.
        if (raw != null && !raw.contains('.')) {
            val secret =
                authSecretRepository
                    .findBySecretHashAndStatus(
                        SecretService.sha256Hex(raw),
                        AuthSecretStatus.ACTIVE,
                    )
                    .orElse(null)
            if (secret != null) {
                auditLogRepository.save(
                    AuditLog(
                        ownerId = secret.ownerId,
                        secretId = secret.id,
                        role = secret.role,
                        action = "${request.method} ${request.requestURI}",
                    )
                )
                val role =
                    when (secret.role!!) {
                        AuthSecretRole.TENANT -> ROLE_TENANT
                        AuthSecretRole.LOGIN_OPERATOR -> ROLE_LOGIN_OPERATOR
                    }
                val jwt = tokenService.mint(secret.ownerId!!, role).token
                filterChain.doFilter(withBearer(request, jwt), response)
                return
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun bearer(header: String?): String? =
        header
            ?.let {
                when {
                    it.startsWith("Bearer ") || it.startsWith("bearer ") -> it.substring(7).trim()
                    else -> it.trim()
                }
            }
            ?.ifEmpty { null }

    private fun withBearer(request: HttpServletRequest, jwt: String): HttpServletRequest =
        object : HttpServletRequestWrapper(request) {
            override fun getHeader(name: String): String? =
                if (name.equals("Authorization", true)) "Bearer $jwt" else super.getHeader(name)

            override fun getHeaders(name: String): Enumeration<String> =
                if (name.equals("Authorization", true))
                    Collections.enumeration(listOf("Bearer $jwt"))
                else super.getHeaders(name)
        }
}
