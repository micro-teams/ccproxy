package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * one metered API call (account is CCProxy-internal and not exposed)
 *
 * @param id
 * @param tenantId
 * @param machineId
 * @param model
 * @param inputTokens
 * @param outputTokens
 * @param at
 * @param cacheReadTokens
 * @param cacheWriteTokens
 */
data class UsageEntryDTO(
    @Schema(required = true, description = "")
    @param:JsonProperty("id")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("tenantId")
    @get:JsonProperty("tenantId", required = true)
    val tenantId: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("machineId")
    @get:JsonProperty("machineId", required = true)
    val machineId: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("model")
    @get:JsonProperty("model", required = true)
    val model: kotlin.String,
    @Schema(required = true, description = "")
    @param:JsonProperty("inputTokens")
    @get:JsonProperty("inputTokens", required = true)
    val inputTokens: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("outputTokens")
    @get:JsonProperty("outputTokens", required = true)
    val outputTokens: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("at")
    @get:JsonProperty("at", required = true)
    val at: java.time.OffsetDateTime,
    @Schema(description = "")
    @param:JsonProperty("cacheReadTokens")
    @get:JsonProperty("cacheReadTokens")
    val cacheReadTokens: kotlin.Long? = null,
    @Schema(description = "")
    @param:JsonProperty("cacheWriteTokens")
    @get:JsonProperty("cacheWriteTokens")
    val cacheWriteTokens: kotlin.Long? = null,
) {}
