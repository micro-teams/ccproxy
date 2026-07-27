package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param code
 * @param message
 * @param error
 */
data class ErrorDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("code", required = true)
    val code: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("message", required = true)
    val message: kotlin.String,
    @Schema(example = "null", description = "")
    @get:JsonProperty("error")
    val error: kotlin.String? = null,
) {}
