package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Omit `host` for a connector-mode machine: it is not SSHed into; instead the response carries a
 * `deviceToken` the machine presents (via `ccproxy-connector connect --token`) to dial back in.
 * Provide `host` for a legacy SSH-provisioned machine.
 *
 * @param host SSH target address; omit for a connector-mode machine
 * @param sshUser SSH user (default: root)
 * @param sshPort SSH port (default: 22)
 * @param label
 */
data class CreateMachineRequestDTO(
    @Schema(example = "null", description = "SSH target address; omit for a connector-mode machine")
    @get:JsonProperty("host")
    val host: kotlin.String? = null,
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
