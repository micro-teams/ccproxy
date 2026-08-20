package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param token
 * @param expiresAt epoch seconds when the token expires
 */
data class SuperadminLoginResponseDTO(
    @Schema(required = true, description = "")
    @param:JsonProperty("token")
    @get:JsonProperty("token", required = true)
    val token: kotlin.String,
    @Schema(required = true, description = "epoch seconds when the token expires")
    @param:JsonProperty("expiresAt")
    @get:JsonProperty("expiresAt", required = true)
    val expiresAt: kotlin.Long,
) {}
