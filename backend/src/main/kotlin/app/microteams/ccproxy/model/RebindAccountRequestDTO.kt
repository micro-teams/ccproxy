package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param accountId */
data class RebindAccountRequestDTO(
    @Schema(required = true, description = "")
    @param:JsonProperty("accountId")
    @get:JsonProperty("accountId", required = true)
    val accountId: kotlin.Long
) {}
