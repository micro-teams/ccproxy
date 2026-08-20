package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
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
    @Schema(required = true, description = "")
    @param:JsonProperty("id")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("machineId")
    @get:JsonProperty("machineId", required = true)
    val machineId: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("tenantId")
    @get:JsonProperty("tenantId", required = true)
    val tenantId: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("accountId")
    @get:JsonProperty("accountId", required = true)
    val accountId: kotlin.Long,
    @Schema(required = true, description = "which Anthropic identity to authenticate as")
    @param:JsonProperty("accountEmail")
    @get:JsonProperty("accountEmail", required = true)
    val accountEmail: kotlin.String,
    @field:Valid
    @Schema(required = true, description = "")
    @param:JsonProperty("status")
    @get:JsonProperty("status", required = true)
    val status: LoginRequestStatusDTO,
    @Schema(description = "")
    @param:JsonProperty("machineLabel")
    @get:JsonProperty("machineLabel")
    val machineLabel: kotlin.String? = null,
    @Schema(description = "proxy the operator's browser should use for the login")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("accountProxy")
    @get:JsonProperty("accountProxy")
    val accountProxy: kotlin.String? = null,
    @Schema(description = "")
    @param:JsonProperty("accountRemark")
    @get:JsonProperty("accountRemark")
    val accountRemark: kotlin.String? = null,
    @Schema(description = "URL to open; present once status is awaiting-code")
    @param:JsonProperty("oauthUrl")
    @get:JsonProperty("oauthUrl")
    val oauthUrl: kotlin.String? = null,
    @Schema(description = "")
    @param:JsonProperty("error")
    @get:JsonProperty("error")
    val error: kotlin.String? = null,
    @Schema(description = "")
    @param:JsonProperty("createdAt")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
    @Schema(description = "epoch seconds; the OAuth URL/window expiry")
    @param:JsonProperty("expiresAt")
    @get:JsonProperty("expiresAt")
    val expiresAt: kotlin.Long? = null,
) {}
