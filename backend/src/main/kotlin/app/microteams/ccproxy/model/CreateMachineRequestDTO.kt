package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param host address CCProxy will SSH into (pubkey already injected)
 * @param sshUser SSH user (default: root)
 * @param sshPort SSH port (default: 22)
 * @param label
 */
data class CreateMachineRequestDTO(
    @Schema(
        example = "null",
        required = true,
        description = "address CCProxy will SSH into (pubkey already injected)",
    )
    @get:JsonProperty("host", required = true)
    val host: kotlin.String,
    @Schema(example = "null", description = "SSH user (default: root)")
    @get:JsonProperty("sshUser")
    val sshUser: kotlin.String? = null,
    @Schema(example = "null", description = "SSH port (default: 22)")
    @get:JsonProperty("sshPort")
    val sshPort: kotlin.Int? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("label")
    val label: kotlin.String? = null,
) {}
