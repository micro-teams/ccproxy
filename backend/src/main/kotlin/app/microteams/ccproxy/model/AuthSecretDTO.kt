package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * secret metadata; the value is never returned here
 *
 * @param id
 * @param ownerId tenant id or login-operator id
 * @param status
 * @param label
 * @param createdAt
 * @param lastUsedAt
 */
data class AuthSecretDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "tenant id or login-operator id")
    @get:JsonProperty("ownerId", required = true)
    val ownerId: kotlin.Long,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: AuthSecretStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("label")
    val label: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("lastUsedAt")
    val lastUsedAt: java.time.OffsetDateTime? = null,
) {}
