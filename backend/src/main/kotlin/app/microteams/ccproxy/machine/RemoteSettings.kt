/*
 *  Description: Server-side editor for a machine's ~/.claude/settings.json. The file is co-owned by
 *               the tenant/user (newapi env, any other Claude settings), so every edit reads the file
 *               back over SSH (cat), does the JSON surgery HERE with Jackson — touching only the keys
 *               we own — and writes it back atomically (base64'd content -> tmp -> mv). No code runs
 *               on the machine beyond cat / base64 / mv, so there is no python3 dependency and no
 *               remote-quoting to get wrong.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Base64
import org.springframework.stereotype.Component

@Component
class RemoteSettings(private val operatorSsh: OperatorSsh, private val mapper: ObjectMapper) {

    /**
     * Birth init: drop the MITM CA and MERGE the engine proxy into env, preserving every other key.
     */
    fun mergeProxy(machine: Machine, proxyUrl: String, caPem: String) {
        val caPath = "${home(machine)}/.claude/ccproxy-ca.crt"
        val caB64 = Base64.getEncoder().encodeToString(caPem.toByteArray())
        run(machine, "mkdir -p \"\$HOME/.claude\"; echo $caB64 | base64 -d > \"$caPath\"")
        val cfg = read(machine)
        val env = envOf(cfg)
        env.put("HTTPS_PROXY", proxyUrl)
        env.put("HTTP_PROXY", proxyUrl)
        env.put("NODE_EXTRA_CA_CERTS", caPath)
        cfg.replace("env", env)
        writeSettings(machine, cfg)
    }

    /**
     * Switch to official: remove ONLY the third-party gateway override keys from env, so a
     * newly-started Claude falls back to the official endpoint (still through the engine proxy,
     * which swaps the fake token for the real one). All other keys — including the proxy we own —
     * stay.
     */
    fun switchToOfficial(machine: Machine) {
        val cfg = read(machine)
        val env = cfg.get("env") as? ObjectNode ?: return
        env.remove(listOf("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"))
        cfg.replace("env", env)
        writeSettings(machine, cfg)
    }

    /**
     * Write the login-only settings file (~/.claude/ccproxy-login.json) that forces the official
     * endpoint + engine proxy + CA and blanks any gateway token — passed to `claude --settings` so
     * the login Claude runs official-via-OAuth without touching the machine's real settings.json.
     */
    fun writeLoginSettings(machine: Machine, proxyUrl: String) {
        val caPath = "${home(machine)}/.claude/ccproxy-ca.crt"
        val env = mapper.createObjectNode()
        env.put("ANTHROPIC_BASE_URL", "https://api.anthropic.com")
        env.put("ANTHROPIC_AUTH_TOKEN", "")
        env.put("ANTHROPIC_API_KEY", "")
        env.put("HTTPS_PROXY", proxyUrl)
        env.put("HTTP_PROXY", proxyUrl)
        env.put("NODE_EXTRA_CA_CERTS", caPath)
        val cfg = mapper.createObjectNode()
        cfg.replace("env", env)
        writeFile(machine, "\$HOME/.claude/ccproxy-login.json", mapper.writeValueAsString(cfg))
    }

    /**
     * The login user's absolute home, resolved once over SSH (NODE_EXTRA_CA_CERTS needs an absolute
     * path). Defensive against any stray banner line: take the last non-blank line.
     */
    private fun home(machine: Machine): String =
        run(machine, "printf '%s\\n' \"\$HOME\"")
            .trim()
            .lineSequence()
            .map { it.trim() }
            .lastOrNull {
                it.isNotEmpty()
            } ?: throw IllegalStateException("could not resolve \$HOME on machine ${machine.id}")

    private fun read(machine: Machine): ObjectNode {
        val raw =
            runCatching {
                    run(machine, "cat \"\$HOME/.claude/settings.json\" 2>/dev/null || true")
                }
                .getOrDefault("")
        // run() merges stderr into stdout, and a remote shell can prepend noise to it (e.g. ssh
        // forwards the client locale and the machine prints "bash: warning: setlocale ..."). Carve
        // out the JSON object between the first '{' and the last '}' so such noise can't turn a
        // populated settings.json into an empty read — which would silently clobber the user's
        // keys.
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return mapper.createObjectNode()
        return runCatching { mapper.readTree(raw.substring(start, end + 1)) as? ObjectNode }
            .getOrNull() ?: mapper.createObjectNode()
    }

    private fun envOf(cfg: ObjectNode): ObjectNode =
        cfg.get("env") as? ObjectNode ?: mapper.createObjectNode()

    private fun writeSettings(machine: Machine, cfg: ObjectNode) =
        writeFile(machine, "\$HOME/.claude/settings.json", mapper.writeValueAsString(cfg))

    /** Atomic remote write: base64 the content (no quoting), decode into a tmp, then mv over. */
    private fun writeFile(machine: Machine, shellPath: String, content: String) {
        val b64 = Base64.getEncoder().encodeToString(content.toByteArray())
        run(
            machine,
            "mkdir -p \"\$HOME/.claude\"; echo $b64 | base64 -d > \"$shellPath.ccproxy.tmp\" && " +
                "mv -f \"$shellPath.ccproxy.tmp\" \"$shellPath\"",
        )
    }

    private fun run(machine: Machine, remote: String): String =
        operatorSsh.run(
            machine.sshUser,
            machine.host!!,
            machine.sshPort,
            remote,
            timeoutSeconds = 30,
        )
}
