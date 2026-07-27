package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * provide either the combined `codeState`, or `code` (+ `state`)
 *
 * @param codeState the browser's `code#state` string, verbatim
 * @param code
 * @param state
 */
data class SubmitLoginCodeRequestDTO(
    @Schema(example = "null", description = "the browser's `code#state` string, verbatim")
    @get:JsonProperty("codeState")
    val codeState: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("code")
    val code: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("state")
    val state: kotlin.String? = null,
) {}
