/*
 *  Description: The Machine entity — one Claude Code login on one SSH target (user@host:port). It
 *               carries the per-machine proxy credentials the proxy-engine uses to tell this
 *               machine's traffic apart, its bound account (internal), provisioning/login status,
 *               and (fake) credential expiry. The real OAuth tokens live on the proxy-engine keyed
 *               by this machine's fake token, never in this table.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class MachineStatus {
    CREATED,
    PROVISIONING,
    AWAITING_LOGIN,
    LOGGING_IN,
    READY,
    ERROR,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "machine",
    indexes = [Index(columnList = "tenant_id"), Index(columnList = "account_id")],
)
class Machine(
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(name = "account_id") var accountId: IdType? = null,
    @Column var label: String? = null,
    /** SSH target address. Null for a connector-mode machine (it dials out; we never SSH in). */
    @Column var host: String? = null,
    @Column(name = "ssh_user", nullable = false) var sshUser: String = "root",
    @Column(name = "ssh_port", nullable = false) var sshPort: Int = 22,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MachineStatus = MachineStatus.CREATED,
    @Column(length = 1024) var error: String? = null,
    @Column(name = "ca_cert_installed", nullable = false) var caCertInstalled: Boolean = false,
    /** Per-machine proxy-auth the engine keys sessions by (HTTPS_PROXY user:pass). */
    @Column(name = "proxy_user", nullable = false) var proxyUser: String? = null,
    @Column(name = "proxy_password", nullable = false) var proxyPassword: String? = null,
    /**
     * The durable device token a connector-mode machine holds and presents at the WebSocket
     * handshake (header X-Microteams-Session). Issued at creation; the only credential the machine
     * carries (every machine has one). Revocable on its own by rotating it.
     */
    @Column(name = "device_token") var deviceToken: String? = null,
    @Column(name = "has_credential", nullable = false) var hasCredential: Boolean = false,
    @Column(name = "credential_expires_at") var credentialExpiresAt: Long? = null,
    @Column(name = "current_login_request_id") var currentLoginRequestId: IdType? = null,
    @Column(name = "last_used_at") var lastUsedAt: java.time.LocalDateTime? = null,
) : BaseEntity()

interface MachineRepository : JpaRepository<Machine, IdType> {
    fun findByTenantId(tenantId: IdType): List<Machine>

    fun countByAccountId(accountId: IdType): Long

    fun findByProxyUser(proxyUser: String): Machine?

    fun findByDeviceToken(deviceToken: String): Machine?
}
