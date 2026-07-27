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
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
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
                operatorSsh.runScript(
                    machine.sshUser,
                    host,
                    machine.sshPort,
                    tmp,
                    "sudo bash -s",
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

    private fun readCaCert(): String {
        val path = config.ca.certPath ?: throw IllegalStateException("no CA cert path configured")
        val f = File(path)
        if (!f.isFile) throw IllegalStateException("CA cert not found at $path")
        return f.readText()
    }

    private fun provisionScript(caPem: String, httpsProxyUrl: String): String =
        """
        set -euo pipefail
        cat > /usr/local/share/ca-certificates/ccproxy-ca.crt <<'CCPROXY_CA_EOF'
        $caPem
        CCPROXY_CA_EOF
        update-ca-certificates
        # Point all HTTPS traffic at the proxy-engine for every login shell.
        grep -q '^HTTPS_PROXY=' /etc/environment && sed -i '/^HTTPS_PROXY=/d' /etc/environment || true
        grep -q '^HTTP_PROXY=' /etc/environment && sed -i '/^HTTP_PROXY=/d' /etc/environment || true
        echo "HTTPS_PROXY=$httpsProxyUrl" >> /etc/environment
        echo "HTTP_PROXY=$httpsProxyUrl" >> /etc/environment
        echo "https_proxy=$httpsProxyUrl" >> /etc/environment
        echo "http_proxy=$httpsProxyUrl" >> /etc/environment
        """
            .trimIndent()
}
