/*
 *  Description: CRUD for the account pool (super-admin only) plus the internal auto-bind used when a
 *               machine is created. Machine count is derived from the machine table.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.account

import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.common.helper.PageHelper
import app.microteams.ccproxy.machine.MachineRepository
import app.microteams.ccproxy.model.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun AccountStatus.toDTO() =
    when (this) {
        AccountStatus.ACTIVE -> AccountStatusDTO.active
        AccountStatus.DISABLED -> AccountStatusDTO.disabled
    }

fun AccountStatusDTO.toEntity() =
    when (this) {
        AccountStatusDTO.active -> AccountStatus.ACTIVE
        AccountStatusDTO.disabled -> AccountStatus.DISABLED
    }

@Service
@Transactional
class AccountService(
    private val repository: AccountRepository,
    private val machineRepository: MachineRepository,
    private val config: CCProxyConfig,
) {
    fun get(id: IdType): Account =
        repository.findById(id).orElseThrow { NotFoundError("account", id) }

    fun toDTO(a: Account) =
        AccountDTO(
            id = a.id!!,
            email = a.email!!,
            proxy = a.proxy!!,
            remark = a.remark,
            status = a.status.toDTO(),
            machineCount = machineRepository.countByAccountId(a.id!!).toInt(),
            createdAt = a.createdAt?.atOffset(ZoneOffset.UTC),
        )

    fun getDTO(id: IdType): AccountDTO = toDTO(get(id))

    fun create(req: CreateAccountRequestDTO): AccountDTO {
        val account =
            repository.save(
                Account(
                    email = req.email,
                    proxy =
                        req.proxy?.takeIf { it.isNotBlank() } ?: config.engine.defaultAccountProxy,
                    remark = req.remark,
                )
            )
        return toDTO(account)
    }

    fun list(
        pageStart: IdType?,
        pageSize: Int,
        status: AccountStatusDTO?,
    ): Pair<List<AccountDTO>, PageDTO> {
        val all =
            repository
                .findAll()
                .filter { status == null || it.status == status.toEntity() }
                .sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { toDTO(it) } to info
    }

    fun update(id: IdType, req: UpdateAccountRequestDTO): AccountDTO {
        val a = get(id)
        req.email?.let { a.email = it }
        req.proxy?.let { a.proxy = it }
        req.remark?.let { a.remark = it }
        req.status?.let { a.status = it.toEntity() }
        return toDTO(repository.save(a))
    }

    fun delete(id: IdType) {
        val a = get(id)
        if (machineRepository.countByAccountId(id) > 0) {
            throw app.microteams.ccproxy.common.error.ConflictError(
                "account $id still has machines bound"
            )
        }
        a.deletedAt = LocalDateTime.now()
        repository.save(a)
    }

    /** Internal: pick an active account to bind a new machine to. Null if the pool is empty. */
    fun pickActiveForBinding(): Account? =
        repository.findFirstByStatusOrderByIdAsc(AccountStatus.ACTIVE)
}
