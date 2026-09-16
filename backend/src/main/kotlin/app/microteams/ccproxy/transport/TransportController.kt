/*
 *  Description: GET /lines — the network paths (MultiPath lines) a connector may dial the
 *               MultiPath origin over. Public: a connector needs this before it can even bring up
 *               the substrate, and it describes routes rather than anything about a tenant.
 *
 *               Mirror of micro-teams' TransportController.listLines.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.transport

import app.microteams.ccproxy.api.LinesApi
import app.microteams.ccproxy.model.LineDTO
import app.microteams.ccproxy.model.LineRegistryDTO
import jakarta.servlet.http.HttpServletRequest
import org.rucca.cheese.auth.annotation.NoAuth
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@RestController
class TransportController(private val lines: LineRegistryProperties) : LinesApi {

    /**
     * The configured lines, or the single same-origin line that means "however a connector already
     * reaches this deployment" — the correct answer for a deployment with one public route, which
     * is every deployment until an operator adds a second one.
     */
    @NoAuth
    override fun listLines(): ResponseEntity<LineRegistryDTO> {
        val configured =
            lines.lines.map {
                LineDTO(
                    id = it.id,
                    url = it.url,
                    transport = it.transport,
                    weight = it.weight,
                    foreignOrigin = it.foreignOrigin,
                )
            }
        val registry = configured.ifEmpty {
            listOf(LineDTO(id = "origin", url = "", transport = socketScheme(), weight = 100))
        }
        return ResponseEntity.ok(LineRegistryDTO(lines = registry))
    }

    /**
     * "wss" when the request reached us over TLS, "ws" otherwise. Read off the request at call time
     * rather than injected into the constructor — this controller is a singleton, and a request
     * captured once at construction would answer every later caller with the first caller's
     * headers.
     */
    private fun socketScheme(): String {
        val request =
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
                as HttpServletRequest?
        val forwarded = request?.getHeader("X-Forwarded-Proto")?.substringBefore(',')?.trim()
        val scheme = if (!forwarded.isNullOrEmpty()) forwarded else request?.scheme
        return if (scheme.equals("https", ignoreCase = true)) "wss" else "ws"
    }
}
