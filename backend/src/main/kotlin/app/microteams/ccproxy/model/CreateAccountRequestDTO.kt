package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param email
 * @param proxy defaults to the bundle's egress-proxy if omitted
 * @param remark
 */
data class CreateAccountRequestDTO(
    @Schema(required = true, description = "")
    @param:JsonProperty("email")
    @get:JsonProperty("email", required = true)
    val email: kotlin.String,
    @Schema(description = "defaults to the bundle's egress-proxy if omitted")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("proxy")
    @get:JsonProperty("proxy")
    val proxy: kotlin.String? = null,
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("remark")
    @get:JsonProperty("remark")
    val remark: kotlin.String? = null,
) {}
