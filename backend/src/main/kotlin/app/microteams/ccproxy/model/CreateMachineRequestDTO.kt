package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
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
    @Schema(description = "SSH target for the one-shot bootstrap; omit to install by hand")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("host")
    @get:JsonProperty("host")
    val host: kotlin.String? = null,
    @Schema(description = "SSH user (default: root)")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("sshUser")
    @get:JsonProperty("sshUser")
    val sshUser: kotlin.String? = null,
    @Schema(description = "SSH port (default: 22)")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("sshPort")
    @get:JsonProperty("sshPort")
    val sshPort: kotlin.Int? = null,
    @Schema(description = "")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("label")
    @get:JsonProperty("label")
    val label: kotlin.String? = null,
) {}
