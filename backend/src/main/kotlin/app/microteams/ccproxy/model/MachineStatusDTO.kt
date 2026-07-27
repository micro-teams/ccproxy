package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * created → provisioning (SSH: install CA + set HTTPS_PROXY) → awaitingLogin → loggingIn → ready.
 * error is terminal-until-retried. Values: created,provisioning,awaitingLogin,loggingIn,ready,error
 */
enum class MachineStatusDTO(@get:JsonValue val value: kotlin.String) {

    created("created"),
    provisioning("provisioning"),
    awaitingLogin("awaitingLogin"),
    loggingIn("loggingIn"),
    ready("ready"),
    error("error");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): MachineStatusDTO {
            return values().first { it -> it.value == value }
        }
    }
}
