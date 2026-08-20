package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param items
 * @param page
 */
data class ListLoginOperatorsResponseDTO(
    @field:Valid
    @Schema(required = true, description = "")
    @param:JsonProperty("items")
    @get:JsonProperty("items", required = true)
    val items: kotlin.collections.List<LoginOperatorDTO>,
    @field:Valid
    @Schema(required = true, description = "")
    @param:JsonProperty("page")
    @get:JsonProperty("page", required = true)
    val page: PageDTO,
) {}
