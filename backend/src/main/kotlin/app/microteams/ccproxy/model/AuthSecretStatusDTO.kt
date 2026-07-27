package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Values: active,revoked */
enum class AuthSecretStatusDTO(@get:JsonValue val value: kotlin.String) {

    active("active"),
    revoked("revoked");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): AuthSecretStatusDTO {
            return values().first { it -> it.value == value }
        }
    }
}
