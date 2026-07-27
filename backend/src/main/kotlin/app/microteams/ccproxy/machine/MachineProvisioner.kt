/*
 *  Description: The SSH side of a machine's provisioning — every method is @Async and lands a
 *               terminal status. provision() waits for SSH, installs the MITM CA cert into the
 *               machine's trust store, points its HTTPS_PROXY at the proxy-engine with this
 *               machine's own proxy-auth, registers the session with the engine, and lands
 *               AWAITING_LOGIN (or ERROR).
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
                // Already root over SSH → no sudo (and minimal images often lack it); otherwise
                // escalate.
                val runner = if (machine.sshUser == "root") "bash -s" else "sudo bash -s"
                operatorSsh.runScript(
                    machine.sshUser,
                    host,
                    machine.sshPort,
                    tmp,
                    runner,
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
        // base64 the PEM onto a single line: a heredoc here would break under trimIndent (the PEM's
        // own lines sit at column 0, so nothing is stripped and the closing delimiter keeps its
        // indent, so the heredoc never terminates and the cert is written as garbage).
        val caB64 = Base64.getEncoder().encodeToString(caPem.toByteArray())
        return """
        set -euo pipefail
        echo "$caB64" | base64 -d > /usr/local/share/ca-certificates/ccproxy-ca.crt
        update-ca-certificates
        # Route HTTPS at the proxy-engine and point Node/Claude Code at our CA. /etc/environment is
        # read by PAM (interactive ssh/login) only; a process started by a bash LOGIN shell — e.g. an
        # agent runner doing `bash -lc "... claude"` — does NOT read it. So ALSO drop a profile.d
        # script, which /etc/profile sources for every login shell, or such a Claude Code would run
        # with no proxy, send its (fake) ccproxy token straight to the real API, and get logged out.
        grep -q '^HTTPS_PROXY=' /etc/environment && sed -i '/^HTTPS_PROXY=/d' /etc/environment || true
        grep -q '^HTTP_PROXY=' /etc/environment && sed -i '/^HTTP_PROXY=/d' /etc/environment || true
        echo "HTTPS_PROXY=$httpsProxyUrl" >> /etc/environment
        echo "HTTP_PROXY=$httpsProxyUrl" >> /etc/environment
        echo "https_proxy=$httpsProxyUrl" >> /etc/environment
        echo "http_proxy=$httpsProxyUrl" >> /etc/environment
        grep -q '^NODE_EXTRA_CA_CERTS=' /etc/environment && sed -i '/^NODE_EXTRA_CA_CERTS=/d' /etc/environment || true
        echo "NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/ccproxy-ca.crt" >> /etc/environment
        echo "export HTTPS_PROXY=$httpsProxyUrl" > /etc/profile.d/ccproxy-proxy.sh
        echo "export HTTP_PROXY=$httpsProxyUrl" >> /etc/profile.d/ccproxy-proxy.sh
        echo "export https_proxy=$httpsProxyUrl" >> /etc/profile.d/ccproxy-proxy.sh
        echo "export http_proxy=$httpsProxyUrl" >> /etc/profile.d/ccproxy-proxy.sh
        echo "export NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/ccproxy-ca.crt" >> /etc/profile.d/ccproxy-proxy.sh
        """
            .trimIndent()
    }
}
