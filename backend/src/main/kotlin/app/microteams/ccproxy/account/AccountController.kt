/*
 *  Description: Super-admin CRUD for the account pool. Fully internal to CCProxy — no tenant access.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.account

import app.microteams.ccproxy.api.AccountApi
import app.microteams.ccproxy.model.*
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.cheese.auth.annotation.ResourceId
import org.rucca.cheese.common.persistent.IdType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AccountController(private val service: AccountService) : AccountApi {

    @Guard("list-accounts", "account")
    override fun listAccounts(
        pageStart: Long?,
        pageSize: Int,
        status: AccountStatusDTO?,
    ): ResponseEntity<ListAccountsResponseDTO> {
        val (items, page) = service.list(pageStart, pageSize, status)
        return ResponseEntity.ok(ListAccountsResponseDTO(items = items, page = page))
    }

    @Guard("create-account", "account")
    override fun createAccount(
        @RequestBody createAccountRequestDTO: CreateAccountRequestDTO
    ): ResponseEntity<AccountDTO> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(createAccountRequestDTO))

    @Guard("get-account", "account")
    override fun getAccount(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<AccountDTO> = ResponseEntity.ok(service.getDTO(id))

    @Guard("update-account", "account")
    override fun updateAccount(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody updateAccountRequestDTO: UpdateAccountRequestDTO,
    ): ResponseEntity<AccountDTO> = ResponseEntity.ok(service.update(id, updateAccountRequestDTO))

    @Guard("delete-account", "account")
    override fun deleteAccount(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<Unit> {
        service.delete(id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }
}
