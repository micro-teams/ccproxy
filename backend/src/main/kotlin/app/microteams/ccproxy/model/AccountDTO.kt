package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * Anthropic identity metadata + egress. Holds NO tokens (login is per-machine). `proxy` is the
 * upstream HTTP proxy a bound machine's API traffic goes out through, and the network the operator
 * should also use when logging in as this account.
 *
 * @param id
 * @param email
 * @param proxy upstream proxy URL, e.g. http://egress-proxy:7890
 * @param status
 * @param remark
 * @param machineCount machines currently bound to this account
 * @param createdAt
 */
data class AccountDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("email", required = true)
    val email: kotlin.String,
    @Schema(
        example = "null",
        required = true,
        description = "upstream proxy URL, e.g. http://egress-proxy:7890",
    )
    @get:JsonProperty("proxy", required = true)
    val proxy: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: AccountStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("remark")
    val remark: kotlin.String? = null,
    @Schema(example = "null", description = "machines currently bound to this account")
    @get:JsonProperty("machineCount")
    val machineCount: kotlin.Int? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
