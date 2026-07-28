/*
 *  Description: Builders for the tiny python programs that surgically edit a machine's
 *               ~/.claude/settings.json. The file is co-owned by the tenant/user (newapi env, any
 *               other Claude settings), so every edit is a read-modify-write that touches only the
 *               keys we own and preserves the rest, written back atomically (tmp + os.replace).
 *               Each builder returns python source (interpolated values are quote-free — proxyUser is
 *               m<id>, password is [A-Za-z0-9]) meant to be base64'd and piped to `python3` so no
 *               shell quoting is ever involved. Pieces are composed by joining independently
 *               indent-trimmed blocks with newlines, so python's indentation stays intact.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.machine

object SettingsPatch {
    /** Load ~/.claude/settings.json as a dict + its env dict (tolerating missing/garbage). */
    private val load =
        """
        import json, os, base64
        P = os.path.expanduser("~/.claude/settings.json")
        try:
            CFG = json.load(open(P)) if os.path.exists(P) and os.path.getsize(P) else {}
        except Exception:
            CFG = {}
        if not isinstance(CFG, dict):
            CFG = {}
        ENV = CFG.get("env")
        if not isinstance(ENV, dict):
            ENV = {}
        """
            .trimIndent()

    /** Write env back and store CFG atomically. */
    private val store =
        """
        CFG["env"] = ENV
        TMP = P + ".ccproxy.tmp"
        json.dump(CFG, open(TMP, "w"), indent=2)
        os.replace(TMP, P)
        """
            .trimIndent()

    /**
     * Birth init: drop the MITM CA next to settings.json and MERGE the engine proxy into env,
     * preserving every other key. [caB64] is the PEM base64'd; [proxyUrl] carries this machine's
     * proxy-auth.
     */
    fun mergeProxyScript(caB64: String, proxyUrl: String): String =
        listOf(
                load,
                """
                D = os.path.expanduser("~/.claude")
                os.makedirs(D, exist_ok=True)
                CA = os.path.join(D, "ccproxy-ca.crt")
                with open(CA, "wb") as f:
                    f.write(base64.b64decode("$caB64"))
                ENV["HTTPS_PROXY"] = "$proxyUrl"
                ENV["HTTP_PROXY"] = "$proxyUrl"
                ENV["NODE_EXTRA_CA_CERTS"] = CA
                """
                    .trimIndent(),
                store,
            )
            .joinToString("\n")

    /**
     * Write the login-only settings file (~/.claude/ccproxy-login.json) that forces the official
     * endpoint + engine proxy + CA and blanks any gateway token. Passed to `claude --settings` so
     * the login Claude runs official-via-OAuth without touching the machine's real settings.json.
     */
    fun writeLoginSettingsScript(proxyUrl: String): String =
        """
        import json, os
        D = os.path.expanduser("~/.claude")
        os.makedirs(D, exist_ok=True)
        cfg = {"env": {
            "ANTHROPIC_BASE_URL": "https://api.anthropic.com",
            "ANTHROPIC_AUTH_TOKEN": "",
            "ANTHROPIC_API_KEY": "",
            "HTTPS_PROXY": "$proxyUrl",
            "HTTP_PROXY": "$proxyUrl",
            "NODE_EXTRA_CA_CERTS": os.path.join(D, "ccproxy-ca.crt"),
        }}
        json.dump(cfg, open(os.path.join(D, "ccproxy-login.json"), "w"))
        """
            .trimIndent()

    /**
     * Switch to official: remove ONLY the third-party gateway override keys from env, so a
     * newly-started Claude falls back to the official endpoint (still through the engine proxy,
     * which swaps the fake token for the real one). All other keys — including the proxy we own —
     * stay.
     */
    fun switchToOfficialScript(): String =
        listOf(
                load,
                """
                for k in ("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"):
                    ENV.pop(k, None)
                """
                    .trimIndent(),
                store,
            )
            .joinToString("\n")
}
