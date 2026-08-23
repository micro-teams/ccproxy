package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param accountId account to switch this machine to */
data class SwitchAccountRequestDTO(
    @Schema(required = true, description = "account to switch this machine to")
    @param:JsonProperty("accountId")
    @get:JsonProperty("accountId", required = true)
    val accountId: kotlin.Long
) {}
