package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Values: active,suspended */
enum class TenantStatusDTO(@get:JsonValue val value: kotlin.String) {

    active("active"),
    suspended("suspended");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): TenantStatusDTO {
            return values().first { it -> it.value == value }
        }
    }
}
