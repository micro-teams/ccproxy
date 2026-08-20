package app.microteams.ccproxy.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
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
    @Schema(required = true, description = "")
    @param:JsonProperty("id")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(required = true, description = "")
    @param:JsonProperty("tenantId")
    @get:JsonProperty("tenantId", required = true)
    val tenantId: kotlin.Long,
    @field:Valid
    @Schema(required = true, description = "")
    @param:JsonProperty("status")
    @get:JsonProperty("status", required = true)
    val status: MachineStatusDTO,
    @Schema(required = true, description = "")
    @param:JsonProperty("caCertInstalled")
    @get:JsonProperty("caCertInstalled", required = true)
    val caCertInstalled: kotlin.Boolean,
    @Schema(
        required = true,
        description = "whether the machine currently holds a (fake) credential",
    )
    @param:JsonProperty("hasCredential")
    @get:JsonProperty("hasCredential", required = true)
    val hasCredential: kotlin.Boolean,
    @Schema(required = true, description = "connector-mode (dial-out) machine, not SSH-provisioned")
    @param:JsonProperty("connector")
    @get:JsonProperty("connector", required = true)
    val connector: kotlin.Boolean,
    @Schema(
        required = true,
        description = "connector-mode: whether the connector is currently dialed in",
    )
    @param:JsonProperty("online")
    @get:JsonProperty("online", required = true)
    val online: kotlin.Boolean,
    @Schema(description = "")
    @param:JsonProperty("label")
    @get:JsonProperty("label")
    val label: kotlin.String? = null,
    @Schema(description = "address CCProxy SSHes into and the proxy identifies")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("host")
    @get:JsonProperty("host")
    val host: kotlin.String? = null,
    @Schema(description = "SSH login user (default root)")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("sshUser")
    @get:JsonProperty("sshUser")
    val sshUser: kotlin.String? = null,
    @Schema(description = "SSH port (default 22)")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:JsonSetter(nulls = Nulls.SKIP)
    @param:JsonProperty("sshPort")
    @get:JsonProperty("sshPort")
    val sshPort: kotlin.Int? = null,
    @Schema(description = "last provisioning/login error")
    @param:JsonProperty("error")
    @get:JsonProperty("error")
    val error: kotlin.String? = null,
    @Schema(description = "epoch seconds; when re-login is needed")
    @param:JsonProperty("credentialExpiresAt")
    @get:JsonProperty("credentialExpiresAt")
    val credentialExpiresAt: kotlin.Long? = null,
    @Schema(description = "an in-flight login, if any")
    @param:JsonProperty("currentLoginRequestId")
    @get:JsonProperty("currentLoginRequestId")
    val currentLoginRequestId: kotlin.Long? = null,
    @Schema(description = "")
    @param:JsonProperty("lastUsedAt")
    @get:JsonProperty("lastUsedAt")
    val lastUsedAt: java.time.OffsetDateTime? = null,
    @Schema(description = "")
    @param:JsonProperty("createdAt")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
    @Schema(
        description =
            "connector-mode credential the machine presents to dial in (`ccproxy-connector connect --token <deviceToken>`). Returned to the owning tenant so it can connect the machine."
    )
    @param:JsonProperty("deviceToken")
    @get:JsonProperty("deviceToken")
    val deviceToken: kotlin.String? = null,
    @Schema(
        description =
            "connector-mode: the one-line install + connect command for this machine (create response only)"
    )
    @param:JsonProperty("installCommand")
    @get:JsonProperty("installCommand")
    val installCommand: kotlin.String? = null,
    @Schema(description = "internal; super-admin only")
    @param:JsonProperty("boundAccountId")
    @get:JsonProperty("boundAccountId")
    val boundAccountId: kotlin.Long? = null,
    @Schema(description = "internal; super-admin only")
    @param:JsonProperty("boundAccountEmail")
    @get:JsonProperty("boundAccountEmail")
    val boundAccountEmail: kotlin.String? = null,
) {}
