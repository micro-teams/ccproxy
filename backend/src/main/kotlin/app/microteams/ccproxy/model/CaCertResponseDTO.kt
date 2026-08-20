package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param pem the CA certificate in PEM */
data class CaCertResponseDTO(
    @Schema(required = true, description = "the CA certificate in PEM")
    @param:JsonProperty("pem")
    @get:JsonProperty("pem", required = true)
    val pem: kotlin.String
) {}
