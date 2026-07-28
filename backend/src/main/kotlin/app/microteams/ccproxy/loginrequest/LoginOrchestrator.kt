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
import java.util.Base64
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
            // engine (so the OAuth token exchange is intercepted).
            val proxyUrl =
                "http://${machine.proxyUser}:${machine.proxyPassword}@${config.engine.proxyEndpoint}"
            // A too-small pane makes Claude Code exit ("terminal too small"); a very wide pane also
            // keeps the long OAuth URL on a single unwrapped line so it scrapes whole.
            sshRun(machine, "tmux kill-session -t $session 2>/dev/null || true")
            sshRun(
                machine,
                // Launch via a login shell AND prepend ~/.local/bin to PATH: the claude.ai
                // installer
                // drops `claude` in ~/.local/bin, which a normal user's profile adds to PATH but
                // root's does not — a bare `claude` would then not be found and the session would
                // die. Setting PATH explicitly works for both root and non-root machines.
                // Force this login process onto the official Anthropic endpoint via OAuth,
                // regardless of the machine's current config. The machine may point at a
                // third-party gateway (e.g. newapi) via ANTHROPIC_BASE_URL / ANTHROPIC_AUTH_TOKEN
                // / ANTHROPIC_API_KEY (env or ~/.claude); if the login Claude inherited that, it
                // would run in API-key mode (no OAuth) or hit the gateway, so the engine would
                // never capture a real token and login would fail. Unset the key/token (forces
                // OAuth) and pin the base URL to official (env overrides ~/.claude) — inside the
                // -c so it runs after the login shell sources /etc/profile.d.
                "tmux new-session -d -s $session -x 1000 -y 50 " +
                    "-e HTTPS_PROXY=$proxyUrl -e HTTP_PROXY=$proxyUrl " +
                    "-e NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/ccproxy-ca.crt " +
                    "bash -lc 'unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN; " +
                    "export ANTHROPIC_BASE_URL=https://api.anthropic.com; " +
                    "PATH=\"\$HOME/.local/bin:\$PATH\" claude'",
            )

            val url = driveToOAuthUrl(machine, session)
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

            // The engine reports the swap slightly BEFORE Claude Code finishes: Claude still has to
            // receive the fake tokens, show "Login successful — press Enter to continue", and
            // persist
            // ~/.claude/.credentials.json. Advance past that prompt and wait for the file to
            // actually
            // land before tearing the session down, or the machine ends up READY with no local
            // credential (interactive `claude` would then say "Not logged in").
            sshRun(machine, "tmux send-keys -t $session Enter")
            for (i in 0 until 20) {
                Thread.sleep(1000)
                val landed =
                    runCatching {
                            sshRun(
                                machine,
                                "test -s ~/.claude/.credentials.json && echo OK || true",
                            )
                        }
                        .getOrDefault("")
                if (landed.contains("OK")) break
            }

            machine.hasCredential = true
            machine.credentialExpiresAt = expiresAt
            machine.status = MachineStatus.READY
            machine.currentLoginRequestId = null
            machineRepository.save(machine)

            // The credential is valid, but interactive `claude` would still re-run its first-run
            // wizard (including a login step) unless onboarding is marked complete — so mark it.
            markOnboardingComplete(machine)

            req.status = LoginRequestStatus.COMPLETED
            loginRequestRepository.save(req)
            sshRun(machine, "tmux kill-session -t $session 2>/dev/null || true")
            log.info("login-request {} completed (machine {} ready)", req.id, machine.id)
        } catch (e: Exception) {
            log.warn("login-request {} apply failed: {}", loginRequestId, e.message)
            failRequest(req, machine, e.message)
        }
    }

    /**
     * Mark Claude Code's first-run onboarding complete on the machine, so interactive `claude` uses
     * the captured credential directly instead of re-running the wizard (theme/login). Best-effort:
     * base64 a tiny Python merge (avoids all SSH quoting), with a plain-write fallback if python is
     * absent; never fails the login.
     */
    private fun markOnboardingComplete(machine: Machine) {
        val script =
            """
            import json, os
            p = os.path.expanduser("~/.claude.json")
            d = json.load(open(p)) if os.path.exists(p) and os.path.getsize(p) else {}
            d["hasCompletedOnboarding"] = True
            json.dump(d, open(p, "w"))
            """
                .trimIndent()
        val b64 = Base64.getEncoder().encodeToString(script.toByteArray())
        try {
            sshRun(
                machine,
                "echo $b64 | base64 -d | python3 || " +
                    "printf '%s' '{\"hasCompletedOnboarding\":true}' > ~/.claude.json",
            )
        } catch (e: Exception) {
            log.warn("could not mark onboarding complete on machine {}: {}", machine.id, e.message)
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

    /**
     * Drive Claude Code to emit an OAuth authorize URL, then return it. Instead of assuming a fixed
     * keystroke sequence, this inspects the pane each tick and reacts to whatever screen is
     * showing, so it works for BOTH a brand-new machine (first-run wizard: theme -> login method ->
     * OAuth auto-starts) and an already-onboarded one (main prompt -> must run `/login`), and
     * tolerates cross-version wording changes. Each distinct screen is acted on once (debounced by
     * action) so a slow screen isn't double-advanced.
     */
    private fun driveToOAuthUrl(machine: Machine, session: String): String {
        val deadline =
            System.currentTimeMillis() + config.provisioning.loginUrlTimeoutSeconds * 1000
        var lastAction = ""
        var loginIssued = false
        Thread.sleep(4000)
        while (System.currentTimeMillis() < deadline) {
            // -J joins wrapped lines so a long URL split across pane rows is captured whole.
            val pane = sshRun(machine, "tmux capture-pane -t $session -p -J -S -200")
            urlRegex.find(pane)?.let {
                return it.value.trim()
            }
            val p = pane.lowercase()
            val action =
                when {
                    // First-run wizard: choose the terminal theme.
                    p.contains("text style") || p.contains("dark mode") -> "theme"
                    // First-run wizard: choose the login method (default #1 = Claude subscription).
                    p.contains("login method") || p.contains("select login") -> "method"
                    // Per-directory trust/safety prompt.
                    p.contains("trust") && p.contains("folder") -> "trust"
                    // Already onboarded, sitting at the main prompt but not logged in: run /login.
                    !loginIssued &&
                        (p.contains("/login") ||
                            p.contains("manual mode") ||
                            p.contains("shortcuts")) -> "login"
                    else -> "wait"
                }
            if (action != "wait" && action != lastAction) {
                when (action) {
                    "login" -> {
                        sshRun(machine, "tmux send-keys -t $session '/login'")
                        Thread.sleep(500)
                        sshRun(machine, "tmux send-keys -t $session Enter")
                        loginIssued = true
                    }
                    else -> sshRun(machine, "tmux send-keys -t $session Enter")
                }
                lastAction = action
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
