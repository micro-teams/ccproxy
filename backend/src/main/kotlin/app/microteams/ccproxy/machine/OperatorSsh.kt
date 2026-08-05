/*
 *  Description: The backend's operator SSH identity and the minimal SSH operations the one-shot
 *               connector bootstrap needs: read the operator public key (injected into machines),
 *               wait for a machine to accept TCP :22, and run a single command (capturing output).
 *               Used only by MachineProvisioner to install the connector and dial it in — the backend
 *               no longer SSH-drives a machine's settings or login; that all goes over the connector.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

import app.microteams.ccproxy.common.config.CCProxyConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import org.springframework.stereotype.Component

@Component
class OperatorSsh(private val config: CCProxyConfig) {

    /** The operator SSH public key injected into machines; the configured value or `${key}.pub`. */
    fun publicKey(): String? {
        config.provisioning.sshPublicKey
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
        val keyPath =
            config.provisioning.sshPrivateKeyPath?.takeIf { it.isNotBlank() } ?: return null
        val pub = File("$keyPath.pub")
        return if (pub.isFile) pub.readText().trim().ifBlank { null } else null
    }

    fun privateKeyPath(): String? =
        config.provisioning.sshPrivateKeyPath?.takeIf { it.isNotBlank() }

    /** Poll TCP :22 until the machine accepts a connection. */
    fun waitForSsh(host: String, port: Int, timeoutSeconds: Long) {
        var waited = 0L
        while (waited < timeoutSeconds) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
                return
            } catch (e: Exception) {
                Thread.sleep(3000)
                waited += 3
            }
        }
        throw IllegalStateException(
            "$host:$port did not become SSH-reachable within ${timeoutSeconds}s"
        )
    }

    /** Run [remote] as [user] on [host]:[port]; returns stdout+stderr; throws on non-zero exit. */
    fun run(user: String, host: String, port: Int, remote: String, timeoutSeconds: Long): String {
        val keyPath = privateKeyPath() ?: throw IllegalStateException("no operator SSH key")
        val (code, output) =
            exec(baseArgs(keyPath, user, host, port) + remote, null, timeoutSeconds)
        if (code != 0) throw IllegalStateException("ssh '$remote' on $host failed ($code): $output")
        return output
    }

    private fun baseArgs(keyPath: String, user: String, host: String, port: Int): List<String> =
        listOf(
            "ssh",
            "-i",
            keyPath,
            "-p",
            port.toString(),
            "-o",
            "StrictHostKeyChecking=no",
            "-o",
            "UserKnownHostsFile=/dev/null",
            // Suppress ssh's own "Warning: Permanently added ... known hosts" — it goes to stderr,
            // which we merge into stdout, and would otherwise pollute output we parse (e.g. $HOME).
            "-o",
            "LogLevel=ERROR",
            "-o",
            "ConnectTimeout=20",
            "$user@$host",
        )

    private fun exec(
        args: List<String>,
        stdinFile: File?,
        timeoutSeconds: Long,
    ): Pair<Int, String> {
        val builder = ProcessBuilder(args).redirectErrorStream(true)
        if (stdinFile != null) builder.redirectInput(stdinFile)
        val process = builder.start()
        if (stdinFile == null) process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("ssh timed out after ${timeoutSeconds}s")
        }
        return process.exitValue() to output
    }
}
