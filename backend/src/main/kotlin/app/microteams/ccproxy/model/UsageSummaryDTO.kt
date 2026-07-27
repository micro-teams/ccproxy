package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * Time-bucketed usage history over [since, until) at `bucketSeconds` resolution. This is all
 * CCProxy provides for billing — MicroCloud does its own metering/quota on top of it.
 *
 * @param since
 * @param until
 * @param bucketSeconds bucket width, e.g. 60 for 1-minute buckets
 * @param buckets
 */
data class UsageSummaryDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("since", required = true)
    val since: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("until", required = true)
    val until: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "bucket width, e.g. 60 for 1-minute buckets",
    )
    @get:JsonProperty("bucketSeconds", required = true)
    val bucketSeconds: kotlin.Int,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("buckets", required = true)
    val buckets: kotlin.collections.List<UsageBucketDTO>,
) {}
