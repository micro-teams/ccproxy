package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * A long-lived OAuth token from `claude setup-token` (sk-ant-oat01-...), minted OUTSIDE ccproxy.
 * Stored server-side; used to activate machines without an interactive /login.
 *
 * @param oauthToken
 */
data class SetSetupTokenRequestDTO(
    @Schema(required = true, description = "")
    @param:JsonProperty("oauthToken")
    @get:JsonProperty("oauthToken", required = true)
    val oauthToken: kotlin.String
) {}
