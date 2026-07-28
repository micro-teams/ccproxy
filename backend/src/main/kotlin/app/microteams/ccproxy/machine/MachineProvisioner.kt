/*
 *  Description: The SSH side of a machine's provisioning — every method is @Async and lands a
 *               terminal status. provision() waits for SSH, then writes ONE file — the login user's
 *               ~/.claude/settings.json — whose `env` block points Claude Code's HTTPS_PROXY at the
 *               proxy-engine (with this machine's own proxy-auth) and its NODE_EXTRA_CA_CERTS at the
 *               MITM CA we drop alongside it. settings.json's env overrides shell/system env and is
 *               read by every Claude process this user starts, so only Claude's traffic is proxied —
 *               no system-wide env files, no CA trust-store install, no sudo. Then it registers the
 *               session with the engine and lands AWAITING_LOGIN (or ERROR).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

import app.microteams.ccproxy.account.AccountRepository
import app.microteams.ccproxy.common.config.CCProxyConfig
import java.io.File
import java.util.Base64
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class MachineProvisioner(
    private val config: CCProxyConfig,
    private val machineRepository: MachineRepository,
    private val accountRepository: AccountRepository,
    private val operatorSsh: OperatorSsh,
    private val engineClient: EngineClient,
) {
    private val log = LoggerFactory.getLogger(MachineProvisioner::class.java)

    @Async
    @Transactional
    fun provision(machineId: Long) {
        // The caller's @Transactional may not have committed yet when this async task starts, so
        // the row may not be visible on the first read — wait briefly for it to commit.
        val machine = awaitRow { machineRepository.findById(machineId).orElse(null) } ?: return
        try {
            machine.status = MachineStatus.PROVISIONING
            machine.error = null
            machineRepository.save(machine)

            val host = machine.host!!
            operatorSsh.waitForSsh(
                host,
                machine.sshPort,
                config.provisioning.sshReadyTimeoutSeconds,
            )

            val caPem = readCaCert()
            val proxyUrl = machine.httpsProxyUrl(config.engine.proxyEndpoint)
            val script = provisionScript(caPem, proxyUrl)
            val tmp = File.createTempFile("ccproxy-init-", ".sh")
            try {
                tmp.writeText(script)
                // Everything lands under the login user's own ~/.claude — no root, no sudo (minimal
                // images often lack it anyway). Run the script as that user so $HOME resolves to
                // it.
                operatorSsh.runScript(
                    machine.sshUser,
                    host,
                    machine.sshPort,
                    tmp,
                    "bash -s",
                    timeoutSeconds = 120,
                )
            } finally {
                tmp.delete()
            }
            machine.caCertInstalled = true

            val account =
                machine.accountId?.let { accountRepository.findById(it).orElse(null) }
                    ?: throw IllegalStateException("machine ${machine.id} has no bound account")
            engineClient.registerSession(
                machine.proxyUser!!,
                machine.proxyPassword!!,
                account.proxy!!,
            )

            machine.status = MachineStatus.AWAITING_LOGIN
            machineRepository.save(machine)
            log.info("provisioned machine {}", machine.id)
        } catch (e: Exception) {
            log.warn("provisioning machine {} failed: {}", machineId, e.message)
            machine.status = MachineStatus.ERROR
            machine.error = e.message?.take(1000)
            machineRepository.save(machine)
        }
    }

    /** Retry a row lookup for a short while, to absorb the caller-transaction commit race. */
    private fun <T> awaitRow(load: () -> T?): T? {
        for (attempt in 0 until 20) {
            load()?.let {
                return it
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun readCaCert(): String {
        val path = config.ca.certPath ?: throw IllegalStateException("no CA cert path configured")
        val f = File(path)
        if (!f.isFile) throw IllegalStateException("CA cert not found at $path")
        return f.readText()
    }

    private fun provisionScript(caPem: String, httpsProxyUrl: String): String {
        // base64 the PEM onto a single line: it's decoded into the CA file on the machine. (A
        // heredoc
        // would break under trimIndent — the PEM's own lines sit at column 0, so the closing
        // delimiter keeps its indent and the heredoc never terminates.)
        val caB64 = Base64.getEncoder().encodeToString(caPem.toByteArray())
        // Write the CA and a single-line settings.json (avoids any heredoc/indent pitfalls). The
        // proxy URL is inlined single-quoted — it never contains a quote (user is m<id>, password
        // is
        // [A-Za-z0-9]); the CA path uses the runtime ${'$'}HOME so it works for root and non-root.
        // ${'$'} is the shell's $ (Kotlin interpolates $httpsProxyUrl / $caB64 only).
        return """
        set -euo pipefail
        D="${'$'}HOME/.claude"
        mkdir -p "${'$'}D"
        echo '$caB64' | base64 -d > "${'$'}D/ccproxy-ca.crt"
        printf '{"env":{"HTTPS_PROXY":"%s","HTTP_PROXY":"%s","NODE_EXTRA_CA_CERTS":"%s"}}\n' '$httpsProxyUrl' '$httpsProxyUrl' "${'$'}D/ccproxy-ca.crt" > "${'$'}D/settings.json"
        """
            .trimIndent()
    }
}
