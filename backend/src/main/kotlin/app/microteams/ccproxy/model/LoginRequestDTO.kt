package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param id
 * @param machineId
 * @param tenantId
 * @param accountId
 * @param accountEmail which Anthropic identity to authenticate as
 * @param status
 * @param machineLabel
 * @param accountProxy proxy the operator's browser should use for the login
 * @param accountRemark
 * @param oauthUrl URL to open; present once status is awaiting-code
 * @param error
 * @param createdAt
 * @param expiresAt epoch seconds; the OAuth URL/window expiry
 */
data class LoginRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("machineId", required = true)
    val machineId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("tenantId", required = true)
    val tenantId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("accountId", required = true)
    val accountId: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "which Anthropic identity to authenticate as",
    )
    @get:JsonProperty("accountEmail", required = true)
    val accountEmail: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: LoginRequestStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("machineLabel")
    val machineLabel: kotlin.String? = null,
    @Schema(example = "null", description = "proxy the operator's browser should use for the login")
    @get:JsonProperty("accountProxy")
    val accountProxy: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("accountRemark")
    val accountRemark: kotlin.String? = null,
    @Schema(example = "null", description = "URL to open; present once status is awaiting-code")
    @get:JsonProperty("oauthUrl")
    val oauthUrl: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("error")
    val error: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
    @Schema(example = "null", description = "epoch seconds; the OAuth URL/window expiry")
    @get:JsonProperty("expiresAt")
    val expiresAt: kotlin.Long? = null,
) {}
