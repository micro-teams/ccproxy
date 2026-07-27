package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param publicKey OpenSSH single-line public key */
data class SshPubkeyResponseDTO(
    @Schema(example = "null", required = true, description = "OpenSSH single-line public key")
    @get:JsonProperty("publicKey", required = true)
    val publicKey: kotlin.String
) {}
