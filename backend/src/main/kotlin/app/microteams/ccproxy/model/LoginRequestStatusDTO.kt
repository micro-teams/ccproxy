package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * preparing (opening tmux / running /login / extracting URL) → awaitingCode (operator can act) →
 * applying (fake code being driven into Claude Code) → completed. failed / expired / cancelled are
 * terminal. Values: preparing,awaitingCode,applying,completed,failed,expired,cancelled
 */
enum class LoginRequestStatusDTO(@get:JsonValue val value: kotlin.String) {

    preparing("preparing"),
    awaitingCode("awaitingCode"),
    applying("applying"),
    completed("completed"),
    failed("failed"),
    expired("expired"),
    cancelled("cancelled");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): LoginRequestStatusDTO {
            return values().firstOrNull { it -> it.value == value }
                ?: throw IllegalArgumentException(
                    "Unexpected value '$value' for enum 'LoginRequestStatusDTO'"
                )
        }
    }
}
