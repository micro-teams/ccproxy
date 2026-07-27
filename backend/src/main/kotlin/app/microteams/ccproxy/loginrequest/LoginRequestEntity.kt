/*
 *  Description: A login-request — one run of the manual OAuth flow for a machine. Tracks the state
 *               machine (preparing → awaiting-code → applying → completed/failed/…), the extracted
 *               OAuth URL for the operator, and the fake code generated for the swap.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.loginrequest

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class LoginRequestStatus {
    PREPARING,
    AWAITING_CODE,
    APPLYING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "login_request",
    indexes = [Index(columnList = "machine_id"), Index(columnList = "status")],
)
class LoginRequest(
    @Column(name = "machine_id", nullable = false) var machineId: IdType? = null,
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(name = "account_id") var accountId: IdType? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: LoginRequestStatus = LoginRequestStatus.PREPARING,
    @Column(name = "oauth_url", length = 2048) var oauthUrl: String? = null,
    @Column(name = "fake_code", length = 128) var fakeCode: String? = null,
    @Column(name = "tmux_session", length = 128) var tmuxSession: String? = null,
    @Column(length = 1024) var error: String? = null,
    @Column(name = "expires_at") var expiresAt: Long? = null,
) : BaseEntity()

interface LoginRequestRepository : JpaRepository<LoginRequest, IdType> {
    fun findByStatus(status: LoginRequestStatus): List<LoginRequest>

    fun findByMachineId(machineId: IdType): List<LoginRequest>
}
