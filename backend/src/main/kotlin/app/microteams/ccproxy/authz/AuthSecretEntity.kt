/*
 *  Description: The opaque-secret table shared by both secret realms (tenant and login-operator),
 *               plus the audit log every secret-authenticated request writes. A secret is stored
 *               only as a SHA-256 hash and carries the role it authenticates as, so one hash lookup
 *               in SecretAuthFilter resolves both the owner and the role to mint.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.authz

import jakarta.persistence.*
import java.util.Optional
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class AuthSecretStatus {
    ACTIVE,
    REVOKED,
}

/** Which realm a secret authenticates as — determines the role its minted JWT carries. */
enum class AuthSecretRole {
    TENANT,
    LOGIN_OPERATOR,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "auth_secret",
    indexes =
        [
            Index(columnList = "owner_id"),
            Index(name = "idx_secret_hash", columnList = "secret_hash", unique = true),
        ],
)
class AuthSecret(
    @Column(name = "owner_id", nullable = false) var ownerId: IdType? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var role: AuthSecretRole? = null,
    @Column(name = "secret_hash", nullable = false, length = 64) var secretHash: String? = null,
    @Column var label: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AuthSecretStatus = AuthSecretStatus.ACTIVE,
) : BaseEntity()

interface AuthSecretRepository : JpaRepository<AuthSecret, IdType> {
    fun findByOwnerIdAndRole(ownerId: IdType, role: AuthSecretRole): List<AuthSecret>

    fun findBySecretHashAndStatus(
        secretHash: String,
        status: AuthSecretStatus,
    ): Optional<AuthSecret>
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "audit_log",
    indexes = [Index(columnList = "owner_id"), Index(columnList = "secret_id")],
)
class AuditLog(
    @Column(name = "owner_id") var ownerId: IdType? = null,
    @Column(name = "secret_id") var secretId: IdType? = null,
    @Enumerated(EnumType.STRING) @Column var role: AuthSecretRole? = null,
    @Column(nullable = false, length = 512) var action: String? = null,
) : BaseEntity()

interface AuditLogRepository : JpaRepository<AuditLog, IdType>
