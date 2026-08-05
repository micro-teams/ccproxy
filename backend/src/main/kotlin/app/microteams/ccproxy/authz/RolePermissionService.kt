/*
 *  Description: The whole authorization matrix in one place. Three roles:
 *               - super-admin: platform operator; manages tenants, login-operators, the account
 *                 pool, and sees all machines/usage.
 *               - tenant: an upstream deployment; manages its own machines (scoped to itself) and
 *                 reads its own usage; never sees the account pool.
 *               - login-operator: performs the manual OAuth step; works the login-request queue.
 *               A JWT carries the role's expanded permission set; @Guard evaluates an endpoint's
 *               (action, resourceType) against it, with predicates registered by the controllers.
 *               Authorization code has no business logic; business code has no authorization.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.authz

import org.rucca.cheese.auth.Authorization
import org.rucca.cheese.auth.AuthorizedResource
import org.rucca.cheese.auth.Permission
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service

const val ROLE_SUPER_ADMIN = "super-admin"
const val ROLE_TENANT = "tenant"
const val ROLE_LOGIN_OPERATOR = "login-operator"

@Service
class RolePermissionService {
    fun getAuthorizationForUserWithRole(userId: IdType, role: String): Authorization =
        when (role) {
            ROLE_SUPER_ADMIN -> superAdmin(userId)
            ROLE_TENANT -> tenant(userId)
            ROLE_LOGIN_OPERATOR -> loginOperator(userId)
            else -> throw IllegalArgumentException("Role '$role' is not supported")
        }

    /**
     * The platform operator, all-powerful by design: every action on every resource — tenants,
     * login-operators, the account pool, machines (unscoped), login-requests, usage.
     */
    private fun superAdmin(userId: IdType): Authorization =
        Authorization(
            userId = userId,
            permissions =
                listOf(
                    grant(listOf("query"), "ping"),
                    grant(
                        listOf(
                            "list-tenants",
                            "create-tenant",
                            "get-tenant",
                            "update-tenant",
                            "delete-tenant",
                            "list-tenant-secrets",
                            "create-tenant-secret",
                            "revoke-tenant-secret",
                        ),
                        "tenant",
                    ),
                    grant(
                        listOf(
                            "list-login-operators",
                            "create-login-operator",
                            "get-login-operator",
                            "update-login-operator",
                            "delete-login-operator",
                            "list-login-operator-secrets",
                            "create-login-operator-secret",
                            "revoke-login-operator-secret",
                        ),
                        "login-operator",
                    ),
                    grant(
                        listOf(
                            "list-accounts",
                            "create-account",
                            "get-account",
                            "update-account",
                            "delete-account",
                        ),
                        "account",
                    ),
                    grant(listOf("get-ssh-pubkey", "get-ca-cert"), "provisioning"),
                    // The platform operator is all-powerful over machines: every action, on ANY
                    // machine (unscoped — no "owned" predicate), so the console can fully manage
                    // the
                    // fleet, not just the four read/rebind actions it used to have.
                    grant(
                        listOf(
                            "list-machine",
                            "get-machine",
                            "create-machine",
                            "update-machine",
                            "delete-machine",
                            "reprovision-machine",
                            "start-machine-login",
                            "rebind-machine-account",
                            "get-machine-usage",
                        ),
                        "machine",
                    ),
                    grant(
                        listOf(
                            "list-login-requests",
                            "get-login-request",
                            "cancel-login-request",
                            "submit-login-code",
                        ),
                        "login-request",
                    ),
                    grant(listOf("list-usage", "get-usage-summary"), "usage"),
                ),
        )

    /** A tenant: its own machines + its own usage; no account-pool visibility. */
    private fun tenant(userId: IdType): Authorization =
        Authorization(
            userId = userId,
            permissions =
                listOf(
                    grant(listOf("query"), "ping"),
                    grant(listOf("get-ssh-pubkey", "get-ca-cert"), "provisioning"),
                    grant(listOf("create-machine", "list-machine"), "machine"),
                    grant(
                        listOf(
                            "get-machine",
                            "update-machine",
                            "delete-machine",
                            "reprovision-machine",
                            "start-machine-login",
                            "get-machine-usage",
                        ),
                        "machine",
                        "owned",
                    ),
                    // Cancelling an in-flight login for one's own machine (owner = the tenant).
                    grant(listOf("cancel-login-request"), "login-request", "owned"),
                    grant(listOf("list-usage", "get-usage-summary"), "usage"),
                ),
        )

    /** A login-operator: the login-request queue only. */
    private fun loginOperator(userId: IdType): Authorization =
        Authorization(
            userId = userId,
            permissions =
                listOf(
                    grant(
                        listOf(
                            "list-login-requests",
                            "get-login-request",
                            "submit-login-code",
                            "cancel-login-request",
                        ),
                        "login-request",
                    )
                ),
        )

    private fun grant(actions: List<String>, resourceType: String, customLogic: String? = null) =
        Permission(
            authorizedActions = actions,
            authorizedResource = AuthorizedResource(types = listOf(resourceType)),
            customLogic = customLogic,
        )
}
