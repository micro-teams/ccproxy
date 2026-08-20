package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param password */
data class SuperadminLoginRequestDTO(
    @Schema(required = true, description = "")
    @param:JsonProperty("password")
    @get:JsonProperty("password", required = true)
    val password: kotlin.String
) {}
