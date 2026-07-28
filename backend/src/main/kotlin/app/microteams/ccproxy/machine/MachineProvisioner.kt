/*
 *  Description: The SSH side of a machine's BIRTH-time initialization — @Async, lands a terminal
 *               status. provision() waits for SSH, then MERGES this machine's proxy into the login
 *               user's ~/.claude/settings.json `env` (HTTPS_PROXY at the proxy-engine with this
 *               machine's own proxy-auth, NODE_EXTRA_CA_CERTS at the MITM CA dropped alongside),
 *               preserving every other key the tenant/user already put there. It binds NO account and
 *               registers NO engine session — birth only points Claude at the engine, which tunnels
 *               unregistered sessions straight through, consuming no account. Account binding and
 *               session registration happen later, at login (see LoginOrchestrator). Lands
 *               AWAITING_LOGIN (or ERROR).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

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
    private val operatorSsh: OperatorSsh,
    private val remoteSettings: RemoteSettings,
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

            // Everything lands under the login user's own ~/.claude — no root, no sudo (minimal
            // images often lack it anyway). The JSON merge happens server-side; the machine only
            // runs cat/base64/mv, so no python3 is needed there.
            remoteSettings.mergeProxy(
                machine,
                machine.httpsProxyUrl(config.engine.proxyEndpoint),
                readCaCert(),
            )
            machine.caCertInstalled = true

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
}
