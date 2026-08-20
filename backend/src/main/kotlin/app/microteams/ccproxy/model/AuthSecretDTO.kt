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
    @Schema(required = true, description = "")
    @param:JsonProperty("id")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(required = true, description = "tenant id or login-operator id")
    @param:JsonProperty("ownerId")
    @get:JsonProperty("ownerId", required = true)
    val ownerId: kotlin.Long,
    @field:Valid
    @Schema(required = true, description = "")
    @param:JsonProperty("status")
    @get:JsonProperty("status", required = true)
    val status: AuthSecretStatusDTO,
    @Schema(description = "")
    @param:JsonProperty("label")
    @get:JsonProperty("label")
    val label: kotlin.String? = null,
    @Schema(description = "")
    @param:JsonProperty("createdAt")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
    @Schema(description = "")
    @param:JsonProperty("lastUsedAt")
    @get:JsonProperty("lastUsedAt")
    val lastUsedAt: java.time.OffsetDateTime? = null,
) {}
