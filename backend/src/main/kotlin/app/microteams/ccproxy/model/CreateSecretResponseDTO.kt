package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * the plaintext value is present ONLY in this response
 *
 * @param id
 * @param secret the plaintext bearer secret; shown once
 * @param label
 */
data class CreateSecretResponseDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "the plaintext bearer secret; shown once",
    )
    @get:JsonProperty("secret", required = true)
    val secret: kotlin.String,
    @Schema(example = "null", description = "")
    @get:JsonProperty("label")
    val label: kotlin.String? = null,
) {}
