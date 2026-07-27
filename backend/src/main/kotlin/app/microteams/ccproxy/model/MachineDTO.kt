package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * One Claude Code login on one SSH target. Identity is the full SSH login (user@host:port), so a
 * host may hold several machines as different users.
 *
 * @param id
 * @param tenantId
 * @param host address CCProxy SSHes into and the proxy identifies
 * @param status
 * @param caCertInstalled
 * @param hasCredential whether the machine currently holds a (fake) credential
 * @param label
 * @param sshUser SSH login user (default root)
 * @param sshPort SSH port (default 22)
 * @param error last provisioning/login error
 * @param httpsProxyUrl The exact value to set as HTTPS_PROXY on the machine — includes the
 *   per-machine proxy credentials the engine uses to tell this machine's traffic apart, e.g.
 *   http://m-42:xxxx@proxy-engine:3128. CCProxy sets it during provisioning; exposed for debugging.
 * @param credentialExpiresAt epoch seconds; when re-login is needed
 * @param currentLoginRequestId an in-flight login, if any
 * @param lastUsedAt
 * @param createdAt
 * @param boundAccountId internal; super-admin only
 * @param boundAccountEmail internal; super-admin only
 */
data class MachineDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("tenantId", required = true)
    val tenantId: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "address CCProxy SSHes into and the proxy identifies",
    )
    @get:JsonProperty("host", required = true)
    val host: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: MachineStatusDTO,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("caCertInstalled", required = true)
    val caCertInstalled: kotlin.Boolean,
    @Schema(
        example = "null",
        required = true,
        description = "whether the machine currently holds a (fake) credential",
    )
    @get:JsonProperty("hasCredential", required = true)
    val hasCredential: kotlin.Boolean,
    @Schema(example = "null", description = "")
    @get:JsonProperty("label")
    val label: kotlin.String? = null,
    @Schema(example = "null", description = "SSH login user (default root)")
    @get:JsonProperty("sshUser")
    val sshUser: kotlin.String? = null,
    @Schema(example = "null", description = "SSH port (default 22)")
    @get:JsonProperty("sshPort")
    val sshPort: kotlin.Int? = null,
    @Schema(example = "null", description = "last provisioning/login error")
    @get:JsonProperty("error")
    val error: kotlin.String? = null,
    @Schema(
        example = "null",
        description =
            "The exact value to set as HTTPS_PROXY on the machine — includes the per-machine proxy credentials the engine uses to tell this machine's traffic apart, e.g. http://m-42:xxxx@proxy-engine:3128. CCProxy sets it during provisioning; exposed for debugging.",
    )
    @get:JsonProperty("httpsProxyUrl")
    val httpsProxyUrl: kotlin.String? = null,
    @Schema(example = "null", description = "epoch seconds; when re-login is needed")
    @get:JsonProperty("credentialExpiresAt")
    val credentialExpiresAt: kotlin.Long? = null,
    @Schema(example = "null", description = "an in-flight login, if any")
    @get:JsonProperty("currentLoginRequestId")
    val currentLoginRequestId: kotlin.Long? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("lastUsedAt")
    val lastUsedAt: java.time.OffsetDateTime? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
    @Schema(example = "null", description = "internal; super-admin only")
    @get:JsonProperty("boundAccountId")
    val boundAccountId: kotlin.Long? = null,
    @Schema(example = "null", description = "internal; super-admin only")
    @get:JsonProperty("boundAccountEmail")
    val boundAccountEmail: kotlin.String? = null,
) {}
