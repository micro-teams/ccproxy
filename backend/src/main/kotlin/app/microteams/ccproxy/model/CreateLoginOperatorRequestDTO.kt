package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param name */
data class CreateLoginOperatorRequestDTO(
    @Schema(required = true, description = "")
    @param:JsonProperty("name")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String
) {}
