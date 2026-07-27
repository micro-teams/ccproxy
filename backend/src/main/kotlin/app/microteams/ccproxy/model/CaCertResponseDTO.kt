package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param pem the CA certificate in PEM */
data class CaCertResponseDTO(
    @Schema(example = "null", required = true, description = "the CA certificate in PEM")
    @get:JsonProperty("pem", required = true)
    val pem: kotlin.String
) {}
