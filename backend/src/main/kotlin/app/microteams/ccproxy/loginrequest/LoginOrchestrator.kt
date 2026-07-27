/*
 *  Description: Drives the manual OAuth flow over SSH, asynchronously. prepare() opens a tmux
 *               session on the machine, runs Claude Code in normal interactive mode, executes
 *               `/login`, and scrapes the OAuth URL (→ awaiting-code). apply() types the fake code
 *               into that waiting session and polls the proxy-engine until the machine holds a
 *               credential (→ machine READY). Claude Code is never run with -p/--console.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.loginrequest

import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.machine.EngineClient
import app.microteams.ccproxy.machine.Machine
import app.microteams.ccproxy.machine.MachineRepository
import app.microteams.ccproxy.machine.MachineStatus
import app.microteams.ccproxy.machine.OperatorSsh
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class LoginOrchestrator(
    private val config: CCProxyConfig,
    private val loginRequestRepository: LoginRequestRepository,
    private val machineRepository: MachineRepository,
    private val operatorSsh: OperatorSsh,
    private val engineClient: EngineClient,
) {
    private val log = LoggerFactory.getLogger(LoginOrchestrator::class.java)
    // Claude Code prints an OAuth authorize URL on claude.com (redirect to platform.claude.com);
    // match any https URL containing "oauth" so we don't couple to a specific host.
    private val urlRegex = Regex("https://\\S*oauth\\S*")

    @Async
    @Transactional
    fun prepare(loginRequestId: Long) {
        val req =
            awaitRow { loginRequestRepository.findById(loginRequestId).orElse(null) } ?: return
        val machine = machineRepository.findById(req.machineId!!).orElse(null) ?: return
        val session = "ccproxy-login-${machine.id}"
        try {
            req.tmuxSession = session
            loginRequestRepository.save(req)

            // Fresh tmux session running Claude Code interactively, with HTTPS_PROXY pointed at the
            // engine (so the OAuth token exchange is intercepted); dismiss the first-run wizard
            // with
            // a couple of Enters, then run /login.
            val proxyUrl =
                "http://${machine.proxyUser}:${machine.proxyPassword}@${config.engine.proxyEndpoint}"
            // A too-small pane makes Claude Code exit ("terminal too small"); a very wide pane also
            // keeps the long OAuth URL on a single unwrapped line so it scrapes whole.
            sshRun(machine, "tmux kill-session -t $session 2>/dev/null || true")
            sshRun(
                machine,
                "tmux new-session -d -s $session -x 1000 -y 50 " +
                    "-e HTTPS_PROXY=$proxyUrl -e HTTP_PROXY=$proxyUrl " +
                    "-e NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/ccproxy-ca.crt claude",
            )
            // First-run wizard: accept the theme, then pick login method #1 (Claude subscription),
            // which drops straight into the OAuth flow and prints the authorize URL.
            Thread.sleep(5000)
            sshRun(machine, "tmux send-keys -t $session Enter")
            Thread.sleep(2500)
            sshRun(machine, "tmux send-keys -t $session Enter")

            val url = pollForUrl(machine, session)
            req.oauthUrl = url
            req.status = LoginRequestStatus.AWAITING_CODE
            req.expiresAt = System.currentTimeMillis() / 1000 + 600
            loginRequestRepository.save(req)
            log.info("login-request {} awaiting code (machine {})", req.id, machine.id)
        } catch (e: Exception) {
            log.warn("login-request {} prepare failed: {}", loginRequestId, e.message)
            failRequest(req, machine, e.message)
        }
    }

    @Async
    @Transactional
    fun apply(loginRequestId: Long, realCode: String, state: String?, fakeCode: String) {
        val req =
            awaitRow { loginRequestRepository.findById(loginRequestId).orElse(null) } ?: return
        val machine = machineRepository.findById(req.machineId!!).orElse(null) ?: return
        val session = req.tmuxSession ?: "ccproxy-login-${machine.id}"
        try {
            req.status = LoginRequestStatus.APPLYING
            req.fakeCode = fakeCode
            loginRequestRepository.save(req)

            // Tell the engine to swap this session's next token exchange, then paste the fake code
            // into the waiting Claude Code prompt.
            engineClient.primeLogin(machine.proxyUser!!, realCode, state, fakeCode)
            val pasted = if (state.isNullOrBlank()) fakeCode else "$fakeCode#$state"
            // Send the code and the Enter as two separate keystrokes: Claude Code's bracketed-paste
            // mode swallows an Enter sent in the same send-keys as the pasted text.
            sshRun(machine, "tmux send-keys -t $session '$pasted'")
            Thread.sleep(700)
            sshRun(machine, "tmux send-keys -t $session Enter")

            // Poll the engine until the machine holds a (fake) credential.
            var expiresAt: Long? = null
            for (i in 0 until 30) {
                Thread.sleep(2000)
                val result = engineClient.getLoginResult(machine.proxyUser!!)
                if (result.hasCredential) {
                    expiresAt = result.expiresAt
                    break
                }
            }
            if (
                expiresAt == null && !engineClient.getLoginResult(machine.proxyUser!!).hasCredential
            ) {
                throw IllegalStateException("credential did not materialize")
            }

            machine.hasCredential = true
            machine.credentialExpiresAt = expiresAt
            machine.status = MachineStatus.READY
            machine.currentLoginRequestId = null
            machineRepository.save(machine)

            req.status = LoginRequestStatus.COMPLETED
            loginRequestRepository.save(req)
            sshRun(machine, "tmux kill-session -t $session 2>/dev/null || true")
            log.info("login-request {} completed (machine {} ready)", req.id, machine.id)
        } catch (e: Exception) {
            log.warn("login-request {} apply failed: {}", loginRequestId, e.message)
            failRequest(req, machine, e.message)
        }
    }

    /** Retry a row lookup briefly, to absorb the caller-transaction commit race. */
    private fun <T> awaitRow(load: () -> T?): T? {
        for (attempt in 0 until 20) {
            load()?.let {
                return it
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun pollForUrl(machine: Machine, session: String): String {
        val deadline =
            System.currentTimeMillis() + config.provisioning.loginUrlTimeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            // -J joins wrapped lines so a long URL split across pane rows is captured whole.
            val pane = sshRun(machine, "tmux capture-pane -t $session -p -J -S -200")
            urlRegex.find(pane)?.let {
                return it.value.trim()
            }
            Thread.sleep(2000)
        }
        throw IllegalStateException("did not observe an OAuth URL within the timeout")
    }

    private fun failRequest(req: LoginRequest, machine: Machine?, message: String?) {
        req.status = LoginRequestStatus.FAILED
        req.error = message?.take(1000)
        loginRequestRepository.save(req)
        if (machine != null) {
            machine.status = MachineStatus.AWAITING_LOGIN
            machine.currentLoginRequestId = null
            machineRepository.save(machine)
        }
    }

    private fun sshRun(machine: Machine, remote: String): String =
        operatorSsh.run(
            machine.sshUser,
            machine.host!!,
            machine.sshPort,
            remote,
            timeoutSeconds = 30,
        )
}
