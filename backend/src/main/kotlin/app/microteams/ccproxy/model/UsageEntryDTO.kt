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
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("tenantId", required = true)
    val tenantId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("machineId", required = true)
    val machineId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("model", required = true)
    val model: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("inputTokens", required = true)
    val inputTokens: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("outputTokens", required = true)
    val outputTokens: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("at", required = true)
    val at: java.time.OffsetDateTime,
    @Schema(example = "null", description = "")
    @get:JsonProperty("cacheReadTokens")
    val cacheReadTokens: kotlin.Long? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("cacheWriteTokens")
    val cacheWriteTokens: kotlin.Long? = null,
) {}
