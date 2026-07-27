package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param label */
data class CreateSecretRequestDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("label")
    val label: kotlin.String? = null
) {}
