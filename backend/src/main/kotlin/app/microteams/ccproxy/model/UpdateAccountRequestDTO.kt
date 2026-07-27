package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
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
    @Schema(example = "null", description = "")
    @get:JsonProperty("email")
    val email: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("proxy")
    val proxy: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("remark")
    val remark: kotlin.String? = null,
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("status")
    val status: AccountStatusDTO? = null,
) {}
