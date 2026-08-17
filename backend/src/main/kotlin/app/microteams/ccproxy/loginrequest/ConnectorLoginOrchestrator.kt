/*
 *  Description: The connector-mode login orchestrator — the same OAuth flow as LoginOrchestrator, but
 *               driven over the connector's dial-out link instead of SSH: files are written with the
 *               hub's `exec` (bash on the machine), and Claude Code's login screen is driven by the
 *               shared claude.js applet in `mode:'login'` (which surfaces the OAuth URL and the login
 *               state as mirrored variables) instead of tmux screen-scraping. The engine interaction
 *               (register session, prime the token swap, poll for the credential) is identical — the
 *               real token is captured by the engine and never touches the machine.
 *
 *               prepare(): configure a login-only settings file + CA, open the claude login screen,
 *               set mode=login, wait for the applet to surface the OAuth URL (→ awaiting-code).
 *               apply(): prime the engine, `say` the fake code into the screen, wait for the engine
 *               to hold the credential and the applet to report success, then point the machine's
 *               ongoing Claude at official-through-engine and mark it READY.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.loginrequest

import app.microteams.ccproxy.account.AccountRepository
import app.microteams.ccproxy.common.config.CCProxyConfig
import app.microteams.ccproxy.credential.CredentialRepository
import app.microteams.ccproxy.credential.CredentialScope
import app.microteams.ccproxy.machine.EngineClient
import app.microteams.ccproxy.machine.Machine
import app.microteams.ccproxy.machine.MachineRepository
import app.microteams.ccproxy.machine.MachineStatus
import app.microteams.ccproxy.machine.link.MachineHub
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.Base64
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ConnectorLoginOrchestrator(
    private val config: CCProxyConfig,
    private val loginRequestRepository: LoginRequestRepository,
    private val machineRepository: MachineRepository,
    private val accountRepository: AccountRepository,
    private val engineClient: EngineClient,
    private val credentialRepository: CredentialRepository,
    private val hub: MachineHub,
    private val appletStore: app.microteams.ccproxy.connector.AppletStore,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ConnectorLoginOrchestrator::class.java)

    @Async
    @Transactional
    fun prepare(loginRequestId: Long) {
        val req =
            awaitRow { loginRequestRepository.findById(loginRequestId).orElse(null) } ?: return
        val machine = machineRepository.findById(req.machineId!!).orElse(null) ?: return
        val mid = machine.id.toString()
        try {
            if (!awaitOnline(mid)) {
                throw IllegalStateException(
                    "machine ${machine.id} connector is not connected " +
                        "(still bootstrapping? give it a moment, or reprovision it)"
                )
            }
            val account =
                machine.accountId?.let { accountRepository.findById(it).orElse(null) }
                    ?: throw IllegalStateException("machine ${machine.id} has no bound account")
            // The engine needs a registered session to MITM this machine's official traffic and
            // capture the real token; the account supplies the egress proxy the session uses.
            engineClient.registerSession(
                machine.proxyUser!!,
                machine.proxyPassword!!,
                account.proxy!!,
            )

            val home = resolveHome(mid)
            val caPath = "$home/.claude/ccproxy-ca.crt"
            writeCaCert(mid, caPath)
            val proxyUrl =
                "http://${machine.proxyUser}:${machine.proxyPassword}@${config.engine.proxyEndpoint}"
            writeLoginSettings(mid, home, proxyUrl, caPath)

            // Launch the login Claude with the login-only settings (CLI-scope, above the machine's
            // own settings.json), driven by the shared claude.js applet in login mode. The pane
            // MUST
            // be wide enough that the OAuth authorize URL stays on ONE line: it is ~300+ chars and
            // `state=` sits at the very end, so if it wraps the capture drops the tail (the
            // operator
            // then gets "Invalid OAuth Request: Missing state parameter"). The SSH path uses -x
            // 1000
            // for the same reason; match it.
            val cmd =
                listOf(
                    "bash",
                    "-lc",
                    "PATH=\"\$HOME/.local/bin:\$PATH\" exec claude --settings " +
                        "\"$home/.claude/ccproxy-login.json\"",
                )
            // A previous login that reached awaiting-code but was never completed (or abandoned)
            // leaves its `claude` login pane running on the machine. Opening another on top stacks
            // full bun runtimes; the newcomer can then starve and miss the 150s OAuth-URL window.
            // Only ever run one login screen per machine: reap any stale one before opening this.
            runCatching { hub.closeScreensOfKind(mid, "login") }

            val screen =
                hub.openScreen(
                    mid,
                    cmd,
                    kind = "login",
                    appletSource = appletStore.require("claude.js"),
                    cols = 1000,
                    rows = 50,
                )
            req.tmuxSession = screen.sid // reuse the field to remember the screen id
            loginRequestRepository.save(req)
            hub.setVar(mid, screen.sid, "mode", "login")

            val url =
                waitForVar(screen.sid, "oauthUrl", timeoutMs = 150_000)
                    ?: throw IllegalStateException(
                        "did not observe an OAuth URL within the timeout"
                    )
            req.oauthUrl = url
            req.status = LoginRequestStatus.AWAITING_CODE
            req.expiresAt = System.currentTimeMillis() / 1000 + 600
            loginRequestRepository.save(req)
            log.info("connector login-request {} awaiting code (machine {})", req.id, machine.id)
        } catch (e: Exception) {
            log.warn("connector login-request {} prepare failed: {}", loginRequestId, e.message)
            failRequest(req, machine)
        }
    }

    /**
     * Setup-token fast path over the connector: inject the account's setup-token as the engine's
     * real credential (getting back a fake), write that fake as CLAUDE_CODE_OAUTH_TOKEN + point
     * ongoing Claude at official-through-engine, mark onboarding complete, and flip to READY — no
     * screen.
     */
    @Async
    @Transactional
    fun prepareViaSetupToken(loginRequestId: Long) {
        val req =
            awaitRow { loginRequestRepository.findById(loginRequestId).orElse(null) } ?: return
        val machine = machineRepository.findById(req.machineId!!).orElse(null) ?: return
        val mid = machine.id.toString()
        try {
            if (!awaitOnline(mid)) {
                throw IllegalStateException(
                    "machine ${machine.id} connector is not connected " +
                        "(still bootstrapping? give it a moment, or reprovision it)"
                )
            }
            val accountId =
                machine.accountId
                    ?: throw IllegalStateException("machine ${machine.id} has no bound account")
            val account =
                accountRepository.findById(accountId).orElse(null)
                    ?: throw IllegalStateException("bound account $accountId not found")
            val cred =
                credentialRepository.findByScopeAndCredKey(
                    CredentialScope.ACCOUNT,
                    accountId.toString(),
                )
            val setupToken =
                cred?.setupToken
                    ?: throw IllegalStateException("account $accountId has no setup-token")

            engineClient.registerSession(
                machine.proxyUser!!,
                machine.proxyPassword!!,
                account.proxy!!,
            )
            val fakeToken =
                engineClient.setCredential(machine.proxyUser!!, setupToken, cred.expiresAt)

            val home = resolveHome(mid)
            val caPath = "$home/.claude/ccproxy-ca.crt"
            writeCaCert(mid, caPath)
            val proxyUrl =
                "http://${machine.proxyUser}:${machine.proxyPassword}@${config.engine.proxyEndpoint}"
            configureOfficialProxy(mid, proxyUrl, caPath, oauthToken = fakeToken)
            markOnboardingComplete(mid)

            machine.hasCredential = true
            machine.caCertInstalled = true
            machine.credentialExpiresAt = cred.expiresAt
            machine.status = MachineStatus.READY
            machine.currentLoginRequestId = null
            machineRepository.save(machine)
            req.status = LoginRequestStatus.COMPLETED
            loginRequestRepository.save(req)
            log.info(
                "connector login-request {} completed via setup-token (machine {})",
                req.id,
                machine.id,
            )
        } catch (e: Exception) {
            log.warn(
                "connector login-request {} setup-token activation failed: {}",
                loginRequestId,
                e.message,
            )
            failRequest(req, machine)
        }
    }

    @Async
    @Transactional
    fun apply(loginRequestId: Long, realCode: String, state: String?, fakeCode: String) {
        val req =
            awaitRow { loginRequestRepository.findById(loginRequestId).orElse(null) } ?: return
        val machine = machineRepository.findById(req.machineId!!).orElse(null) ?: return
        val mid = machine.id.toString()
        val sid =
            req.tmuxSession
                ?: run {
                    failRequest(req, machine)
                    return
                }
        try {
            req.status = LoginRequestStatus.APPLYING
            req.fakeCode = fakeCode
            loginRequestRepository.save(req)

            engineClient.primeLogin(machine.proxyUser!!, realCode, state, fakeCode)
            val pasted = if (state.isNullOrBlank()) fakeCode else "$fakeCode#$state"
            // The driver's `say` bracketed-pastes the code and submits it a couple of frames later
            // —
            // exactly the deferred submit a pasted code needs.
            hub.callScreen(mid, sid, "say", listOf(pasted))

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

            // Belt and suspenders: the engine holds the credential AND the applet reports the login
            // landed. The applet sets loginState=success on Claude's own "Login successful" screen.
            var sawSuccess = false
            for (i in 0 until 20) {
                if (hub.screen(sid)?.vars?.get("loginState") == "success") {
                    sawSuccess = true
                    break
                }
                Thread.sleep(1000)
            }
            if (!sawSuccess) {
                throw IllegalStateException(
                    "login not confirmed (engineCredential=true, applet loginState != success)"
                )
            }

            // Point the machine's ongoing Claude at official-through-engine (proxy + CA, no gateway
            // override); its ~/.claude/.credentials.json already holds the fake token the engine
            // swaps. Mark onboarding complete so interactive claude uses it without re-running
            // /login.
            val home = resolveHome(mid)
            val caPath = "$home/.claude/ccproxy-ca.crt"
            val proxyUrl =
                "http://${machine.proxyUser}:${machine.proxyPassword}@${config.engine.proxyEndpoint}"
            configureOfficialProxy(mid, proxyUrl, caPath, oauthToken = null)
            markOnboardingComplete(mid)

            machine.hasCredential = true
            machine.caCertInstalled = true
            machine.credentialExpiresAt = expiresAt
            machine.status = MachineStatus.READY
            machine.currentLoginRequestId = null
            machineRepository.save(machine)
            req.status = LoginRequestStatus.COMPLETED
            loginRequestRepository.save(req)
            runCatching { hub.closeScreen(mid, sid) }
            runCatching { execScript(mid, "rm -f \"$home/.claude/ccproxy-login.json\"") }
            log.info("connector login-request {} completed (machine {} ready)", req.id, machine.id)
        } catch (e: Exception) {
            log.warn("connector login-request {} apply failed: {}", loginRequestId, e.message)
            failRequest(req, machine)
        }
    }

    // --- machine-side helpers (over the connector's exec) ---------------------

    private fun execScript(machineId: String, script: String) =
        hub.exec(machineId, listOf("bash", "-lc", script), timeoutSeconds = 30)

    private fun resolveHome(machineId: String): String {
        val out = execScript(machineId, "printf %s \"\$HOME\"").stdout.trim()
        return out.ifBlank { "/root" }
    }

    private fun b64(content: String): String =
        Base64.getEncoder().encodeToString(content.toByteArray())

    private fun writeFileOnMachine(machineId: String, absPath: String, content: String) {
        val r =
            execScript(
                machineId,
                "mkdir -p \"\$(dirname '$absPath')\" && echo ${b64(content)} | base64 -d > " +
                    "'$absPath.ccproxy.tmp' && mv -f '$absPath.ccproxy.tmp' '$absPath'",
            )
        if (r.exit != 0) throw IllegalStateException("write $absPath failed: ${r.stderr.take(200)}")
    }

    private fun writeCaCert(machineId: String, caPath: String) {
        val pem = config.ca.certPath?.let { File(it).takeIf(File::isFile)?.readText() }
        if (pem == null) {
            log.warn(
                "no CA cert at {} — connector machine will not trust the MITM",
                config.ca.certPath,
            )
            return
        }
        writeFileOnMachine(machineId, caPath, pem)
    }

    private fun writeLoginSettings(
        machineId: String,
        home: String,
        proxyUrl: String,
        caPath: String,
    ) {
        val env = mapper.createObjectNode()
        env.put("ANTHROPIC_BASE_URL", "https://api.anthropic.com")
        env.put("ANTHROPIC_AUTH_TOKEN", "")
        env.put("ANTHROPIC_API_KEY", "")
        env.put("HTTPS_PROXY", proxyUrl)
        env.put("HTTP_PROXY", proxyUrl)
        env.put("NODE_EXTRA_CA_CERTS", caPath)
        val cfg = mapper.createObjectNode()
        cfg.replace("env", env)
        writeFileOnMachine(
            machineId,
            "$home/.claude/ccproxy-login.json",
            mapper.writeValueAsString(cfg),
        )
    }

    /**
     * Merge the engine proxy + CA into the machine's real settings.json (preserving other keys),
     * drop any newapi gateway override, and — for the setup-token path — plant the fake OAuth
     * token. Read-modify-write over exec so a user's own keys survive.
     */
    private fun configureOfficialProxy(
        machineId: String,
        proxyUrl: String,
        caPath: String,
        oauthToken: String?,
    ) {
        val home = resolveHome(machineId)
        val path = "$home/.claude/settings.json"
        val existing =
            runCatching { execScript(machineId, "cat '$path' 2>/dev/null || true").stdout }
                .getOrDefault("")
        val start = existing.indexOf('{')
        val end = existing.lastIndexOf('}')
        val cfg =
            if (start in 0 until end) {
                runCatching {
                        mapper.readTree(existing.substring(start, end + 1))
                            as? com.fasterxml.jackson.databind.node.ObjectNode
                    }
                    .getOrNull() ?: mapper.createObjectNode()
            } else {
                mapper.createObjectNode()
            }
        val env =
            (cfg.get("env") as? com.fasterxml.jackson.databind.node.ObjectNode)
                ?: mapper.createObjectNode()
        env.remove(listOf("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"))
        env.put("HTTPS_PROXY", proxyUrl)
        env.put("HTTP_PROXY", proxyUrl)
        env.put("NODE_EXTRA_CA_CERTS", caPath)
        if (oauthToken != null) env.put("CLAUDE_CODE_OAUTH_TOKEN", oauthToken)
        cfg.replace("env", env)
        writeFileOnMachine(machineId, path, mapper.writeValueAsString(cfg))
    }

    private fun markOnboardingComplete(machineId: String) {
        val script =
            """
            import json, os
            p = os.path.expanduser("~/.claude.json")
            d = json.load(open(p)) if os.path.exists(p) and os.path.getsize(p) else {}
            d["hasCompletedOnboarding"] = True
            json.dump(d, open(p, "w"))
            """
                .trimIndent()
        runCatching {
            execScript(
                machineId,
                "echo ${b64(script)} | base64 -d | python3 || " +
                    "printf '%s' '{\"hasCompletedOnboarding\":true}' > \"\$HOME/.claude.json\"",
            )
        }
    }

    /**
     * Wait for the machine's connector to dial in. A login issued right after createMachine races
     * the async bootstrap that installs the connector, so give the connector a moment to come
     * online rather than failing the login outright.
     */
    private fun awaitOnline(machineId: String, timeoutSeconds: Long = 120): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (hub.isOnline(machineId)) return true
            Thread.sleep(2000)
        }
        return hub.isOnline(machineId)
    }

    /** Poll a mirrored screen variable until it is non-blank, or the timeout elapses. */
    private fun waitForVar(sid: String, name: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val v = hub.screen(sid)?.vars?.get(name) as? String
            if (!v.isNullOrBlank()) return v
            Thread.sleep(1000)
        }
        return null
    }

    private fun awaitRow(load: () -> LoginRequest?): LoginRequest? {
        for (attempt in 0 until 20) {
            load()?.let {
                return it
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun failRequest(req: LoginRequest?, machine: Machine?) {
        // Don't leave a half-open login pane behind on a failure — it would pile up on the next
        // try.
        val sid = req?.tmuxSession
        if (req != null && machine != null && sid != null) {
            runCatching { hub.closeScreen(machine.id.toString(), sid) }
        }
        if (req != null) {
            req.status = LoginRequestStatus.FAILED
            loginRequestRepository.save(req)
        }
        if (machine != null) {
            machine.status = MachineStatus.AWAITING_LOGIN
            machine.currentLoginRequestId = null
            machineRepository.save(machine)
        }
    }
}
