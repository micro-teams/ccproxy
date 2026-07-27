/*
 *  Description: The Account entity + repository. An account is Anthropic-identity metadata plus its
 *               egress proxy; it holds NO tokens (login is per-machine). Fully internal to CCProxy —
 *               never exposed to tenants.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.account

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class AccountStatus {
    ACTIVE,
    DISABLED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "account", indexes = [Index(columnList = "email")])
class Account(
    @Column(nullable = false) var email: String? = null,
    /**
     * Upstream egress proxy a bound machine's traffic and the operator's login browser go through.
     */
    @Column(nullable = false) var proxy: String? = null,
    @Column var remark: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AccountStatus = AccountStatus.ACTIVE,
) : BaseEntity()

interface AccountRepository : JpaRepository<Account, IdType> {
    fun findFirstByStatusOrderByIdAsc(status: AccountStatus): Account?
}
