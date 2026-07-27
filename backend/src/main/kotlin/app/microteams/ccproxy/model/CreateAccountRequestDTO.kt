package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param email
 * @param proxy defaults to the bundle's egress-proxy if omitted
 * @param remark
 */
data class CreateAccountRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("email", required = true)
    val email: kotlin.String,
    @Schema(example = "null", description = "defaults to the bundle's egress-proxy if omitted")
    @get:JsonProperty("proxy")
    val proxy: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("remark")
    val remark: kotlin.String? = null,
) {}
