/*
 *  Description: The LoginOperator entity + repository. A login-operator is a person/seat that
 *               performs the manual OAuth step; it authenticates with opaque secrets (shared
 *               authz.AuthSecret table, realm = LOGIN_OPERATOR).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.loginoperator

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class LoginOperatorStatus {
    ACTIVE,
    SUSPENDED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "login_operator", indexes = [Index(columnList = "name")])
class LoginOperator(
    @Column(nullable = false) var name: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: LoginOperatorStatus = LoginOperatorStatus.ACTIVE,
) : BaseEntity()

interface LoginOperatorRepository : JpaRepository<LoginOperator, IdType>
