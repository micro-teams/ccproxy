/*
 *  Description: 502 Bad Gateway — the proxy-engine did not confirm a control-plane operation whose
 *               effect MUST be real before we report success (e.g. dropping a machine's live session
 *               on revocation). Surfacing the failure instead of swallowing it is the point: a
 *               revoke that "succeeds" while the engine still serves the session leaves a live
 *               credential spending.
 *
 *  Author(s):
 *      Zhifei Li       <andylizf@gmail.com>
 *
 */

package app.microteams.ccproxy.common.error

import org.rucca.cheese.common.error.BaseError
import org.springframework.http.HttpStatus

class EngineUnavailableError(message: String) : BaseError(HttpStatus.BAD_GATEWAY, message)
