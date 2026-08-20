package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
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
    @Schema(required = true, description = "bucket start, epoch seconds")
    @param:JsonProperty("at")
    @get:JsonProperty("at", required = true)
    val at: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("requests")
    @get:JsonProperty("requests", required = true)
    val requests: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("inputTokens")
    @get:JsonProperty("inputTokens", required = true)
    val inputTokens: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("outputTokens")
    @get:JsonProperty("outputTokens", required = true)
    val outputTokens: kotlin.Long,
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("cacheReadTokens")
    @get:JsonProperty("cacheReadTokens")
    val cacheReadTokens: kotlin.Long? = null,
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("cacheWriteTokens")
    @get:JsonProperty("cacheWriteTokens")
    val cacheWriteTokens: kotlin.Long? = null,
) {}
