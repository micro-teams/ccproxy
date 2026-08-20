package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param code
 * @param message
 * @param error
 */
data class ErrorDTO(
    @Schema(required = true, description = "")
    @param:JsonProperty("code")
    @get:JsonProperty("code", required = true)
    val code: kotlin.Int,
    @Schema(required = true, description = "")
    @param:JsonProperty("message")
    @get:JsonProperty("message", required = true)
    val message: kotlin.String,
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("error")
    @get:JsonProperty("error")
    val error: kotlin.String? = null,
) {}
