package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Non-secret status of an account's setup-token (never the token itself).
 *
 * @param present
 * @param expiresAt epoch seconds if known
 */
data class SetupTokenStatusDTO(
    @Schema(required = true, description = "")
    @param:JsonProperty("present")
    @get:JsonProperty("present", required = true)
    val present: kotlin.Boolean,
    @Schema(description = "epoch seconds if known")
    @param:JsonProperty("expiresAt")
    @get:JsonProperty("expiresAt")
    val expiresAt: kotlin.Long? = null,
) {}
