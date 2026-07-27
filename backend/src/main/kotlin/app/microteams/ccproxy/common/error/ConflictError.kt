/*
 *  Description: 409 Conflict — a precondition for the operation is not met (e.g. deleting an
 *               account that still has machines bound, or an in-flight login already running).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.common.error

import org.rucca.cheese.common.error.BaseError
import org.springframework.http.HttpStatus

class ConflictError(message: String) : BaseError(HttpStatus.CONFLICT, message)
