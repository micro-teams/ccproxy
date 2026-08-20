package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
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
    @Schema(required = true, description = "")
    @param:JsonProperty("id")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("email")
    @get:JsonProperty("email", required = true)
    val email: kotlin.String,
    @Schema(required = true, description = "upstream proxy URL, e.g. http://egress-proxy:7890")
    @param:JsonProperty("proxy")
    @get:JsonProperty("proxy", required = true)
    val proxy: kotlin.String,
    @field:Valid
    @Schema(required = true, description = "")
    @param:JsonProperty("status")
    @get:JsonProperty("status", required = true)
    val status: AccountStatusDTO,
    @Schema(description = "")
    @param:JsonProperty("remark")
    @get:JsonProperty("remark")
    val remark: kotlin.String? = null,
    @Schema(description = "machines currently bound to this account")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("machineCount")
    @get:JsonProperty("machineCount")
    val machineCount: kotlin.Int? = null,
    @Schema(description = "")
    @param:JsonProperty("createdAt")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
