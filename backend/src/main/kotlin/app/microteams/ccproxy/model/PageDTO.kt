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
    @Schema(required = true, description = "")
    @param:JsonProperty("pageSize")
    @get:JsonProperty("pageSize", required = true)
    val pageSize: kotlin.Int,
    @Schema(required = true, description = "")
    @param:JsonProperty("hasPrev")
    @get:JsonProperty("hasPrev", required = true)
    val hasPrev: kotlin.Boolean,
    @Schema(required = true, description = "")
    @param:JsonProperty("hasMore")
    @get:JsonProperty("hasMore", required = true)
    val hasMore: kotlin.Boolean,
    @Schema(description = "")
    @param:JsonProperty("pageStart")
    @get:JsonProperty("pageStart")
    val pageStart: kotlin.Long? = null,
    @Schema(description = "")
    @param:JsonProperty("prevStart")
    @get:JsonProperty("prevStart")
    val prevStart: kotlin.Long? = null,
    @Schema(description = "")
    @param:JsonProperty("nextStart")
    @get:JsonProperty("nextStart")
    val nextStart: kotlin.Long? = null,
) {}
