package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema

/**
 * provide either the combined `codeState`, or `code` (+ `state`)
 *
 * @param codeState the browser's `code#state` string, verbatim
 * @param code
 * @param state
 */
data class SubmitLoginCodeRequestDTO(
    @Schema(description = "the browser's `code#state` string, verbatim")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("codeState")
    @get:JsonProperty("codeState")
    val codeState: kotlin.String? = null,
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("code")
    @get:JsonProperty("code")
    val code: kotlin.String? = null,
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("state")
    @get:JsonProperty("state")
    val state: kotlin.String? = null,
) {}
