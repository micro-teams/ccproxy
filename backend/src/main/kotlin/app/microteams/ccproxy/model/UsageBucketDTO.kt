package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * usage aggregated over one time bucket (bucket start = `at`)
 *
 * @param at bucket start, epoch seconds
 * @param requests
 * @param inputTokens
 * @param outputTokens
 * @param cacheReadTokens
 * @param cacheWriteTokens
 */
data class UsageBucketDTO(
    @Schema(example = "null", required = true, description = "bucket start, epoch seconds")
    @get:JsonProperty("at", required = true)
    val at: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("requests", required = true)
    val requests: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("inputTokens", required = true)
    val inputTokens: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("outputTokens", required = true)
    val outputTokens: kotlin.Long,
    @Schema(example = "null", description = "")
    @get:JsonProperty("cacheReadTokens")
    val cacheReadTokens: kotlin.Long? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("cacheWriteTokens")
    val cacheWriteTokens: kotlin.Long? = null,
) {}
