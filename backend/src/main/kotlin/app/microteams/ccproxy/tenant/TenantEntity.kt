/*
 *  Description: The Tenant entity and its repository. A tenant is one upstream deployment; it
 *               authenticates with opaque secrets (stored in the shared authz.AuthSecret table) and
 *               owns machines.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.tenant

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class TenantStatus {
    ACTIVE,
    SUSPENDED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "tenant", indexes = [Index(columnList = "name")])
class Tenant(
    @Column(nullable = false) var name: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TenantStatus = TenantStatus.ACTIVE,
) : BaseEntity()

interface TenantRepository : JpaRepository<Tenant, IdType>
