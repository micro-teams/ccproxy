/*
 *  Description: The WebSocket endpoint a machine's connector dials out to (/machine/link). On
 *               connect it binds the (already token-verified) machine to a transport in the hub; each
 *               inbound frame is parsed to a LinkMsg and handed to the hub; on close the exact
 *               transport instance is detached (identity matters — a reconnect must not detach the
 *               new one). Message-size limits are raised because applet source and base64 terminal
 *               chunks dwarf the 8 KB JSR-356 default.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine.link

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler

@Component
class LinkHandler(private val hub: MachineHub, private val objectMapper: ObjectMapper) :
    TextWebSocketHandler() {
    private val logger = LoggerFactory.getLogger(LinkHandler::class.java)

    private companion object {
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
    }

    private data class Bound(val machineId: String, val transport: MachineTransport)

    private val bound = ConcurrentHashMap<String, Bound>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val machineId = session.attributes["machineId"] as? String
        if (machineId == null) {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        session.textMessageSizeLimit = MAX_MESSAGE_BYTES
        session.binaryMessageSizeLimit = MAX_MESSAGE_BYTES
        val transport = MachineTransport { msg ->
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(msg)))
        }
        bound[session.id] = Bound(machineId, transport)
        val origin = session.attributes["origin"] as? String
        hub.attachMachine(machineId, transport, origin)
        logger.info("machine {} connected (session {})", machineId, session.id)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val b = bound[session.id] ?: return
        val msg =
            try {
                objectMapper.readValue(message.payload, LinkMsg::class.java)
            } catch (e: Exception) {
                logger.debug("dropping unparseable frame from machine {}", b.machineId)
                return
            }
        hub.onMachineMessage(b.machineId, msg)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val b = bound.remove(session.id) ?: return
        hub.detachMachine(b.machineId, b.transport)
        logger.info("machine {} disconnected (session {}, {})", b.machineId, session.id, status)
    }
}
