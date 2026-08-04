/*
 *  Description: The single durable store for both kinds of real credential, moved off the engine's
 *               app_data/sessions files into Postgres (backend-owned). Two scopes share one
 *               table: SESSION holds a machine's captured/injected tokens keyed by proxyUser (mirrors
 *               the engine's in-memory session fields); ACCOUNT holds an account's optional long-lived
 *               setup-token (oauth token) keyed by accountId, used for the no-/login activation path.
 *               Plaintext by deliberate decision: under the bundle deploy the DB and app_data files
 *               share one host and trust boundary, so DB plaintext is no less safe than the files it
 *               replaces. Engine↔backend transport is guarded by the shared engine secret.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.credential

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class CredentialScope {
    SESSION,
    ACCOUNT,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "credential",
    uniqueConstraints =
        [UniqueConstraint(name = "uq_credential_scope_key", columnNames = ["scope", "cred_key"])],
)
class Credential(
    @Enumerated(EnumType.STRING) @Column(nullable = false) var scope: CredentialScope,
    /** proxyUser for SESSION, accountId (as string) for ACCOUNT. */
    @Column(name = "cred_key", nullable = false) var credKey: String,
    // ── SESSION fields (mirror the engine's per-session token set) ──
    @Column(columnDefinition = "text") var proxyPassword: String? = null,
    @Column(columnDefinition = "text") var accountProxy: String? = null,
    @Column(columnDefinition = "text") var realAccess: String? = null,
    @Column(columnDefinition = "text") var realRefresh: String? = null,
    @Column(columnDefinition = "text") var fakeAccess: String? = null,
    @Column(columnDefinition = "text") var fakeRefresh: String? = null,
    // ── ACCOUNT field (the operator-provided setup-token oauth token) ──
    @Column(columnDefinition = "text") var setupToken: String? = null,
    // Non-secret, redundant for querying/reconciliation (epoch seconds).
    @Column var expiresAt: Long? = null,
) : BaseEntity()

interface CredentialRepository : JpaRepository<Credential, IdType> {
    fun findByScopeAndCredKey(scope: CredentialScope, credKey: String): Credential?

    fun findAllByScope(scope: CredentialScope): List<Credential>
}
