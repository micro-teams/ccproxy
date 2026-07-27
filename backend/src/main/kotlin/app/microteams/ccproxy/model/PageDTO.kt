package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * cursor-pagination metadata
 *
 * @param pageSize
 * @param hasPrev
 * @param hasMore
 * @param pageStart
 * @param prevStart
 * @param nextStart
 */
data class PageDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("pageSize", required = true)
    val pageSize: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("hasPrev", required = true)
    val hasPrev: kotlin.Boolean,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("hasMore", required = true)
    val hasMore: kotlin.Boolean,
    @Schema(example = "null", description = "")
    @get:JsonProperty("pageStart")
    val pageStart: kotlin.Long? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("prevStart")
    val prevStart: kotlin.Long? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("nextStart")
    val nextStart: kotlin.Long? = null,
) {}
