/*
 *  Description: Registers the machine control-channel WebSocket at /machine/link (behind the nginx
 *               /ccproxy/ prefix, which already upgrades WebSockets). Auth is by the machine's device
 *               token in the X-Microteams-Session header — resolved at the handshake and stashed on
 *               the session — so origin checking is not relied on (a dial-out connector has no
 *               meaningful browser Origin).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine.link

import app.microteams.ccproxy.machine.MachineService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.HandshakeInterceptor

@Configuration
@EnableWebSocket
class ConnectorWebSocketConfig(
    private val linkHandler: LinkHandler,
    private val machineService: MachineService,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry
            .addHandler(linkHandler, "/machine/link")
            .addInterceptors(MachineHandshakeInterceptor(machineService))
            .setAllowedOrigins("*")
    }
}

/** Resolves the device token in X-Microteams-Session to a machine, or refuses the handshake. */
class MachineHandshakeInterceptor(private val machineService: MachineService) :
    HandshakeInterceptor {
    private val logger = LoggerFactory.getLogger(MachineHandshakeInterceptor::class.java)

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val token = request.headers.getFirst("X-Microteams-Session")
        if (token.isNullOrBlank()) {
            logger.warn("machine handshake rejected: missing X-Microteams-Session")
            return false
        }
        val machine = machineService.verifyDeviceToken(token)
        if (machine?.id == null) {
            logger.warn("machine handshake rejected: unknown device token")
            return false
        }
        attributes["machineId"] = machine.id.toString()
        // The connector reports the base URL it dialed us on; echoed back to screens as the API
        // base.
        request.headers
            .getFirst("X-Microteams-Origin")
            ?.takeIf { it.isNotBlank() }
            ?.let { attributes["origin"] = it.trimEnd('/') }
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {}
}
