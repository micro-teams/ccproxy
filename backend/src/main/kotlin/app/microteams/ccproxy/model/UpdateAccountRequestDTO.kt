package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * partial update; omitted fields unchanged
 *
 * @param email
 * @param proxy
 * @param remark
 * @param status
 */
data class UpdateAccountRequestDTO(
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("email")
    @get:JsonProperty("email")
    val email: kotlin.String? = null,
    @Schema(description = "")
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
    @field:Valid
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("status")
    @get:JsonProperty("status")
    val status: AccountStatusDTO? = null,
) {}
