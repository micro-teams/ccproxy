package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param requiresManualLogin true if the target account has no setup-token, so an interactive
 *   /login was started and a login-operator must finish it; false if the switch activated
 *   automatically via setup-token.
 * @param loginRequest
 */
data class SwitchAccountResponseDTO(
    @Schema(
        required = true,
        description =
            "true if the target account has no setup-token, so an interactive /login was started and a login-operator must finish it; false if the switch activated automatically via setup-token.",
    )
    @param:JsonProperty("requiresManualLogin")
    @get:JsonProperty("requiresManualLogin", required = true)
    val requiresManualLogin: kotlin.Boolean,
    @field:Valid
    @Schema(required = true, description = "")
    @param:JsonProperty("loginRequest")
    @get:JsonProperty("loginRequest", required = true)
    val loginRequest: LoginRequestDTO,
) {}
