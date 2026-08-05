package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Provide `host` to have the backend bootstrap the connector over SSH (one-shot install + dial in).
 * Omit `host` to install the connector by hand: the response carries a `deviceToken` +
 * `installCommand` to run on the machine. Either way the machine ends up connector-driven.
 *
 * @param host SSH target for the one-shot bootstrap; omit to install by hand
 * @param sshUser SSH user (default: root)
 * @param sshPort SSH port (default: 22)
 * @param label
 */
data class CreateMachineRequestDTO(
    @Schema(
        example = "null",
        description = "SSH target for the one-shot bootstrap; omit to install by hand",
    )
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
