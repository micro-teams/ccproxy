/*
 *  Description: Internal ingest endpoint the proxy-engine POSTs metered usage to. Not part of the
 *               public OpenAPI contract; authenticated by the shared engine secret header rather
 *               than the role framework (hence @NoAuth + a manual check).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.usage

import app.microteams.ccproxy.common.config.CCProxyConfig
import org.rucca.cheese.auth.annotation.NoAuth
import org.rucca.cheese.common.error.BadRequestError
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

data class UsageReport(
    val proxyUser: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
)

@RestController
class EngineIngestController(
    private val usageService: UsageService,
    private val config: CCProxyConfig,
) {
    @NoAuth
    @PostMapping("/internal/usage")
    fun ingest(
        @RequestHeader(value = "X-Engine-Secret", required = false) secret: String?,
        @RequestBody report: UsageReport,
    ): ResponseEntity<Unit> {
        val expected = config.engine.controlSecret
        if (!expected.isNullOrBlank() && secret != expected) {
            throw BadRequestError("invalid engine secret")
        }
        usageService.record(
            report.proxyUser,
            report.model,
            report.inputTokens,
            report.outputTokens,
            report.cacheReadTokens,
            report.cacheWriteTokens,
        )
        return ResponseEntity.ok().build()
    }
}
