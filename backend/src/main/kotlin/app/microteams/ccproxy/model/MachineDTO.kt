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
 * @param status
 * @param caCertInstalled
 * @param hasCredential whether the machine currently holds a (fake) credential
 * @param connector connector-mode (dial-out) machine, not SSH-provisioned
 * @param online connector-mode: whether the connector is currently dialed in
 * @param label
 * @param host address CCProxy SSHes into and the proxy identifies
 * @param sshUser SSH login user (default root)
 * @param sshPort SSH port (default 22)
 * @param error last provisioning/login error
 * @param credentialExpiresAt epoch seconds; when re-login is needed
 * @param currentLoginRequestId an in-flight login, if any
 * @param lastUsedAt
 * @param createdAt
 * @param deviceToken connector-mode credential the machine presents to dial in (`ccproxy-connector
 *   connect --token <deviceToken>`). Returned to the owning tenant so it can connect the machine.
 * @param installCommand connector-mode: the one-line install + connect command for this machine
 *   (create response only)
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
    @Schema(
        example = "null",
        required = true,
        description = "connector-mode (dial-out) machine, not SSH-provisioned",
    )
    @get:JsonProperty("connector", required = true)
    val connector: kotlin.Boolean,
    @Schema(
        example = "null",
        required = true,
        description = "connector-mode: whether the connector is currently dialed in",
    )
    @get:JsonProperty("online", required = true)
    val online: kotlin.Boolean,
    @Schema(example = "null", description = "")
    @get:JsonProperty("label")
    val label: kotlin.String? = null,
    @Schema(example = "null", description = "address CCProxy SSHes into and the proxy identifies")
    @get:JsonProperty("host")
    val host: kotlin.String? = null,
    @Schema(example = "null", description = "SSH login user (default root)")
    @get:JsonProperty("sshUser")
    val sshUser: kotlin.String? = null,
    @Schema(example = "null", description = "SSH port (default 22)")
    @get:JsonProperty("sshPort")
    val sshPort: kotlin.Int? = null,
    @Schema(example = "null", description = "last provisioning/login error")
    @get:JsonProperty("error")
    val error: kotlin.String? = null,
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
    @Schema(
        example = "null",
        description =
            "connector-mode credential the machine presents to dial in (`ccproxy-connector connect --token <deviceToken>`). Returned to the owning tenant so it can connect the machine.",
    )
    @get:JsonProperty("deviceToken")
    val deviceToken: kotlin.String? = null,
    @Schema(
        example = "null",
        description =
            "connector-mode: the one-line install + connect command for this machine (create response only)",
    )
    @get:JsonProperty("installCommand")
    val installCommand: kotlin.String? = null,
    @Schema(example = "null", description = "internal; super-admin only")
    @get:JsonProperty("boundAccountId")
    val boundAccountId: kotlin.Long? = null,
    @Schema(example = "null", description = "internal; super-admin only")
    @get:JsonProperty("boundAccountEmail")
    val boundAccountEmail: kotlin.String? = null,
) {}
