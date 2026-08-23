package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Latest quota utilization scraped from Anthropic's `anthropic-ratelimit-unified-*` response
 * headers on this account's traffic. Read-only observability; null until any traffic has been seen.
 * Utilization is a 0..1 fraction of the window used; reset is epoch seconds.
 *
 * @param fiveHUtilization
 * @param fiveHResetAt
 * @param fiveHStatus
 * @param sevenDUtilization
 * @param sevenDResetAt
 * @param sevenDStatus
 * @param observedAt
 */
data class AccountQuotaDTO(
    @Schema(description = "")
    @param:JsonProperty("fiveHUtilization")
    @get:JsonProperty("fiveHUtilization")
    val fiveHUtilization: kotlin.Double? = null,
    @Schema(description = "")
    @param:JsonProperty("fiveHResetAt")
    @get:JsonProperty("fiveHResetAt")
    val fiveHResetAt: kotlin.Long? = null,
    @Schema(description = "")
    @param:JsonProperty("fiveHStatus")
    @get:JsonProperty("fiveHStatus")
    val fiveHStatus: kotlin.String? = null,
    @Schema(description = "")
    @param:JsonProperty("sevenDUtilization")
    @get:JsonProperty("sevenDUtilization")
    val sevenDUtilization: kotlin.Double? = null,
    @Schema(description = "")
    @param:JsonProperty("sevenDResetAt")
    @get:JsonProperty("sevenDResetAt")
    val sevenDResetAt: kotlin.Long? = null,
    @Schema(description = "")
    @param:JsonProperty("sevenDStatus")
    @get:JsonProperty("sevenDStatus")
    val sevenDStatus: kotlin.String? = null,
    @Schema(description = "")
    @param:JsonProperty("observedAt")
    @get:JsonProperty("observedAt")
    val observedAt: java.time.OffsetDateTime? = null,
) {}
