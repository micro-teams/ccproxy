package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * partial update; omitted fields unchanged
 *
 * @param label
 */
data class UpdateMachineRequestDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("label")
    val label: kotlin.String? = null
) {}
