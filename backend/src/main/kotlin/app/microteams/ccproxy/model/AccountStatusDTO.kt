package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Values: active,disabled */
enum class AccountStatusDTO(@get:JsonValue val value: kotlin.String) {

    active("active"),
    disabled("disabled");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): AccountStatusDTO {
            return values().firstOrNull { it -> it.value == value }
                ?: throw IllegalArgumentException(
                    "Unexpected value '$value' for enum 'AccountStatusDTO'"
                )
        }
    }
}
