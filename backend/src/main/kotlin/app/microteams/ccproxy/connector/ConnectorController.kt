/*
 *  Description: Serves the CLI distribution — the one-line installer and the prebuilt binaries a
 *               fresh machine needs — so a machine that ccproxy cannot SSH into can install the
 *               connector and dial back in:
 *
 *                 GET /install.sh                            → the installer, with the connector
 *                     base / API base / ws override baked in from the request's own origin (plus the
 *                     configured gateway prefix), so `curl -fsSL <origin>/ccproxy/install.sh | sh`
 *                     resolves everything against this deployment with nothing configured.
 *                 GET /connector/latest/{target}/{artifact}  → the `ccproxy-connector` binary or the
 *                     static `tmux` for one <os>-<arch>, streamed from
 *                     application.connector-binaries-dir. This is the endpoint micro-connector's
 *                     self-updater and install.sh download from.
 *
 *               Both ride the existing nginx `/ccproxy/` prefix (which already forwards
 *               X-Forwarded-* and upgrades WebSockets), so no gateway change is needed.
 *
 *               Public (@NoAuth) and hand-written (not an OpenAPI operation) because it serves
 *               shell/binary *assets*, not business DTOs. The installer carries no secrets —
 *               enrolment happens separately with a device token — so serving it openly is safe.
 *
 *               The binary route is the sharp edge: `target` and `artifact` are validated against
 *               strict allowlists, so no attacker-controlled path segment ever reaches the
 *               filesystem (no traversal). Anything off the allowlist, or a not-yet-published file,
 *               is a 404.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.connector

import jakarta.servlet.http.HttpServletRequest
import java.nio.file.Files
import java.nio.file.Path
import org.rucca.cheese.auth.annotation.NoAuth
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class ConnectorController(
    // The whole ccproxy API — including these asset routes — lives behind the gateway's prefix, and
    // the backend can't see it in the request (nginx strips it), so it is a configured constant.
    // The
    // origin (scheme+host) IS derived from the request, so absolute URLs are correct behind any
    // host/port/proxy without baking a domain.
    @Value("\${application.connector-base-path:/ccproxy}") private val basePath: String,
    @Value("\${application.connector-ws-override:}") private val wsOverride: String,
    @Value("\${application.connector-binaries-dir:/app/connector}") private val binariesDir: String,
) {

    // The only shapes install.sh + the self-updater ever request — Go-style os-arch (amd64/arm64),
    // NOT uname-style (x86_64/aarch64); install.sh maps uname to these before asking.
    private val targets = setOf("linux-amd64", "linux-arm64", "darwin-amd64", "darwin-arm64")
    // The only artifacts published per target. Keeps the served path off the filesystem's leash.
    private val artifacts = setOf("ccproxy-connector", "tmux")

    @NoAuth
    @GetMapping("/install.sh", produces = ["text/x-shellscript"])
    fun installScript(request: HttpServletRequest): ResponseEntity<String> {
        val script =
            javaClass.getResourceAsStream("/install.sh")?.bufferedReader()?.use { it.readText() }
                ?: return ResponseEntity.notFound().build()
        // Binaries and the API both hang off <origin><basePath> (e.g. https://host/ccproxy),
        // because
        // that is the one thing nginx maps to this backend.
        val base = origin(request).trimEnd('/') + basePath
        val baked =
            script
                .replace("__CONNECTOR_BASE__", base)
                .replace("__API_BASE__", base)
                .replace("__WS_BASE__", wsOverride)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/x-shellscript"))
            .body(baked)
    }

    @NoAuth
    @GetMapping("/connector/latest/{target}/{artifact}")
    fun artifact(
        @PathVariable target: String,
        @PathVariable artifact: String,
    ): ResponseEntity<Resource> {
        if (target !in targets || artifact !in artifacts) {
            return ResponseEntity.notFound().build()
        }
        val root = Path.of(binariesDir).toAbsolutePath().normalize()
        val file = root.resolve(target).resolve(artifact).normalize()
        // Defense in depth: even though target/artifact are allowlisted (so no `..` is possible),
        // refuse anything that would resolve outside the configured root.
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$artifact\"")
            .contentLength(Files.size(file))
            .body(FileSystemResource(file))
    }

    // The forwarded-header origin the rest of the backend derives: we sit behind nginx, which sets
    // X-Forwarded-Proto/Host; fall back to the request's own when absent. No domain is ever baked
    // in.
    private fun origin(request: HttpServletRequest): String {
        val proto = request.getHeader("X-Forwarded-Proto") ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host") ?: request.getHeader("Host")
        return "$proto://$host"
    }
}
