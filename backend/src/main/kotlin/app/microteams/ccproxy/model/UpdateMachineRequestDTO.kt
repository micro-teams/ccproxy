package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema

/**
 * partial update; omitted fields unchanged
 *
 * @param label
 */
data class UpdateMachineRequestDTO(
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("label")
    @get:JsonProperty("label")
    val label: kotlin.String? = null
) {}
