/*
 *  Description: Machine BIRTH-time bootstrap — @Async, lands a terminal status. This no longer
 *               SSH-drives a machine: for a machine with an SSH host it SSHes in exactly ONCE to
 *               install the connector and dial it back in (`curl <base>/install.sh | sh` then
 *               `ccproxy-connector connect <base> --token <deviceToken>`); after that all config +
 *               login go over the connector's dial-out link, not SSH. A machine with NO host is a
 *               pure connector machine and needs no server-side action — the operator runs the same
 *               install command by hand. Lands AWAITING_LOGIN once the connector is online (or ERROR).
 *
 *               reprovision() runs the same bootstrap, which is also how an existing SSH-provisioned
 *               machine is migrated onto the connector (idempotent; leaves its working
 *               settings.json / credentials untouched, so day-to-day use is uninterrupted).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.machine.link.MachineHub
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class MachineProvisioner(
    private val config: CCProxyConfig,
    private val machineRepository: MachineRepository,
    private val operatorSsh: OperatorSsh,
    private val hub: MachineHub,
    // The public base URL a machine dials the control plane on (origin + gateway prefix, e.g.
    // https://ccproxy.example.com/ccproxy). Baked into the bootstrap command. Required to bootstrap
    // SSH machines; the compose bundle sets it.
    @Value("\${application.connector-public-base:}") private val publicBase: String,
) {
    private val log = LoggerFactory.getLogger(MachineProvisioner::class.java)

    @Async
    @Transactional
    fun provision(machineId: Long) {
        // The caller's @Transactional may not have committed yet when this async task starts, so
        // the row may not be visible on the first read — wait briefly for it to commit.
        val machine = awaitRow { machineRepository.findById(machineId).orElse(null) } ?: return
        // A hostless (connector) machine dials in on its own once the operator runs the installer;
        // there is nothing to do server-side.
        if (machine.host.isNullOrBlank()) return
        try {
            machine.status = MachineStatus.PROVISIONING
            machine.error = null
            machineRepository.save(machine)

            val base =
                publicBase.trimEnd('/').ifBlank {
                    throw IllegalStateException(
                        "application.connector-public-base is not set; cannot bootstrap SSH machines"
                    )
                }
            operatorSsh.waitForSsh(
                machine.host!!,
                machine.sshPort,
                config.provisioning.sshReadyTimeoutSeconds,
            )

            // Install the connector on its own SSH command, as the login user (binary →
            // ~/.local/bin),
            // so its verbose output can't bury the connect error below in the machine's 1000-char
            // error.
            operatorSsh.run(
                machine.sshUser,
                machine.host!!,
                machine.sshPort,
                "curl -fsSL '$base/install.sh' | sh",
                timeoutSeconds = 120,
            )
            // Then dial in. `connect` first PREFLIGHTS the machine's requirements (claude, tmux, …)
            // and exits non-zero with a clear "missing required tooling" message if something is
            // absent — which surfaces here as the machine's ERROR/error, visible on GET /machine,
            // instead of only failing later at login. `connect` installs a boot service where there
            // is one and falls back to a detached run where there isn't.
            operatorSsh.run(
                machine.sshUser,
                machine.host!!,
                machine.sshPort,
                "PATH=\"\$HOME/.local/bin:\$PATH\" ccproxy-connector connect '$base' " +
                    "--token '${machine.deviceToken}'",
                timeoutSeconds = 120,
            )

            if (!waitOnline(machine.id!!.toString(), 90)) {
                throw IllegalStateException("connector did not dial in within 90s after bootstrap")
            }
            machine.status = MachineStatus.AWAITING_LOGIN
            machineRepository.save(machine)
            log.info("bootstrapped machine {} onto the connector", machine.id)
        } catch (e: Exception) {
            log.warn("bootstrapping machine {} failed: {}", machineId, e.message)
            machine.status = MachineStatus.ERROR
            machine.error = e.message?.take(1000)
            machineRepository.save(machine)
        }
    }

    /** Poll the hub until the machine's connector has dialed in, or the timeout elapses. */
    private fun waitOnline(machineId: String, timeoutSeconds: Long): Boolean {
        var waited = 0L
        while (waited < timeoutSeconds) {
            if (hub.isOnline(machineId)) return true
            Thread.sleep(2000)
            waited += 2
        }
        return hub.isOnline(machineId)
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
}
