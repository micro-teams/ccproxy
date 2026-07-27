// The CCProxy test console. Three roles share one console shell, each authenticating on the same
// Authorization: Bearer header the real API uses:
//   - admin    (super-admin JWT from password login): tenants, login-operators, the account pool,
//              and a read-only view of all machines + usage.
//   - tenant   (opaque tenant secret): its own machines — register, trigger login, read usage; plus
//              the provisioning helpers (ssh-pubkey / ca-cert). Never sees the account pool.
//   - operator (opaque login-operator secret): the login-request queue — open the OAuth URL through
//              the account's proxy, then paste the real code back.
// Everything goes through the same public /ccproxy API a real upstream would use.

import { useEffect, useState } from "react";
import { request, superadminLogin } from "./api";
import { ResourcePanel } from "./ui";
import "./styles.css";

type Role = "admin" | "tenant" | "operator";
type Session = { role: Role; token: string };

const STORE_KEY = "ccproxy.session";

function loadSession(): Session | null {
  try {
    return JSON.parse(localStorage.getItem(STORE_KEY) || "null");
  } catch {
    return null;
  }
}

export function App() {
  const [session, setSession] = useState<Session | null>(loadSession);
  function login(s: Session) {
    localStorage.setItem(STORE_KEY, JSON.stringify(s));
    setSession(s);
  }
  function logout() {
    localStorage.removeItem(STORE_KEY);
    setSession(null);
  }
  if (!session) return <Login onLogin={login} />;
  return <Console session={session} onLogout={logout} />;
}

// ── Login ──────────────────────────────────────────────────────────────────────
function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const [tab, setTab] = useState<Role>("admin");
  const [password, setPassword] = useState("");
  const [secret, setSecret] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function go() {
    setBusy(true);
    setErr("");
    try {
      if (tab === "admin") {
        onLogin({ role: "admin", token: await superadminLogin(password) });
      } else if (tab === "tenant") {
        await request("GET", "/machine?page_size=1", { token: secret });
        onLogin({ role: "tenant", token: secret });
      } else {
        await request("GET", "/login-request?page_size=1", { token: secret });
        onLogin({ role: "operator", token: secret });
      }
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <h1>CCProxy</h1>
      <p className="hint">test console</p>
      <div className="tabs">
        <button className={tab === "admin" ? "on" : ""} onClick={() => setTab("admin")}>
          Super-admin
        </button>
        <button className={tab === "tenant" ? "on" : ""} onClick={() => setTab("tenant")}>
          Tenant
        </button>
        <button className={tab === "operator" ? "on" : ""} onClick={() => setTab("operator")}>
          Login-operator
        </button>
      </div>
      <div className="form">
        {tab === "admin" ? (
          <label>
            <span>Operator password</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && go()}
            />
          </label>
        ) : (
          <label>
            <span>{tab === "tenant" ? "Tenant secret" : "Login-operator secret"}</span>
            <input
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
              placeholder="the plaintext secret minted for it"
              onKeyDown={(e) => e.key === "Enter" && go()}
            />
          </label>
        )}
        <button onClick={go} disabled={busy}>
          {tab === "admin" ? "Log in" : "Enter"}
        </button>
      </div>
      {err && <div className="msg err">{err}</div>}
    </main>
  );
}

// ── Console shell ───────────────────────────────────────────────────────────────
function Console({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const tabs =
    session.role === "admin"
      ? ["Tenants", "Login-operators", "Accounts", "Machines", "Usage"]
      : session.role === "tenant"
        ? ["Setup", "Machines", "Usage"]
        : ["Login queue", "Submit code"];
  const [tab, setTab] = useState(tabs[0]);
  const t = session.token;

  return (
    <div className="console">
      <header className="topbar">
        <strong>CCProxy</strong>
        <span className="role">{session.role}</span>
        <nav className="tabs">
          {tabs.map((x) => (
            <button key={x} className={tab === x ? "on" : ""} onClick={() => setTab(x)}>
              {x}
            </button>
          ))}
        </nav>
        <button className="ghost" onClick={onLogout}>
          log out
        </button>
      </header>
      <main className="content">
        {session.role === "admin" && <AdminPanels tab={tab} token={t} />}
        {session.role === "tenant" && <TenantPanels tab={tab} token={t} />}
        {session.role === "operator" && <OperatorPanels tab={tab} token={t} />}
      </main>
    </div>
  );
}

// ── Admin ────────────────────────────────────────────────────────────────────────
function AdminPanels({ tab, token }: { tab: string; token: string }) {
  if (tab === "Tenants")
    return (
      <ResourcePanel
        title="Tenants"
        description="Upstream deployments. Mint a secret it uses as its Bearer token (shown once, in output)."
        columns={[
          { key: "id", label: "id" },
          { key: "name", label: "name" },
          { key: "status", label: "status" },
          { key: "createdAt", label: "created" },
        ]}
        load={() => request("GET", "/tenant", { token })}
        createFields={[{ name: "name", label: "name" }]}
        onCreate={(b) => request("POST", "/tenant", { token, body: b })}
        actions={[
          {
            label: "mint secret",
            run: (r) => request("POST", `/tenant/${r.id}/secret`, { token, body: {} }),
          },
          {
            label: "delete",
            confirm: "delete tenant?",
            run: (r) => request("DELETE", `/tenant/${r.id}`, { token }),
          },
        ]}
      />
    );
  if (tab === "Login-operators")
    return (
      <ResourcePanel
        title="Login-operators"
        description="People/seats that perform the manual OAuth step. Mint a secret (shown once, in output)."
        columns={[
          { key: "id", label: "id" },
          { key: "name", label: "name" },
          { key: "status", label: "status" },
        ]}
        load={() => request("GET", "/login-operator", { token })}
        createFields={[{ name: "name", label: "name" }]}
        onCreate={(b) => request("POST", "/login-operator", { token, body: b })}
        actions={[
          {
            label: "mint secret",
            run: (r) => request("POST", `/login-operator/${r.id}/secret`, { token, body: {} }),
          },
          {
            label: "delete",
            confirm: "delete?",
            run: (r) => request("DELETE", `/login-operator/${r.id}`, { token }),
          },
        ]}
      />
    );
  if (tab === "Accounts")
    return (
      <ResourcePanel
        title="Account pool"
        description="Anthropic identities + egress. No tokens stored (login is per-machine). Internal — tenants never see this."
        columns={[
          { key: "id", label: "id" },
          { key: "email", label: "email" },
          { key: "proxy", label: "proxy" },
          { key: "remark", label: "remark" },
          { key: "status", label: "status" },
          { key: "machineCount", label: "machines" },
        ]}
        load={() => request("GET", "/account", { token })}
        createFields={[
          { name: "email", label: "email" },
          { name: "proxy", label: "proxy", optional: true, placeholder: "default: egress-proxy:7890" },
          { name: "remark", label: "remark", optional: true },
        ]}
        onCreate={(b) => request("POST", "/account", { token, body: b })}
        actions={[
          {
            label: "toggle",
            run: (r) =>
              request("PATCH", `/account/${r.id}`, {
                token,
                body: { status: r.status === "active" ? "disabled" : "active" },
              }),
          },
          {
            label: "delete",
            confirm: "delete account?",
            run: (r) => request("DELETE", `/account/${r.id}`, { token }),
          },
        ]}
      />
    );
  if (tab === "Machines")
    return (
      <ResourcePanel
        title="All machines"
        columns={[
          { key: "id", label: "id" },
          { key: "tenantId", label: "tenant" },
          { key: "host", label: "host" },
          { key: "status", label: "status" },
          { key: "hasCredential", label: "cred", render: (v) => (v ? "yes" : "—") },
          { key: "boundAccountEmail", label: "account" },
        ]}
        load={() => request("GET", "/machine", { token })}
      />
    );
  return <UsagePanel token={token} />;
}

// ── Tenant ───────────────────────────────────────────────────────────────────────
function TenantPanels({ tab, token }: { tab: string; token: string }) {
  if (tab === "Setup") return <SetupPanel token={token} />;
  if (tab === "Machines")
    return (
      <ResourcePanel
        title="Machines"
        description="One machine = one SSH target = one Claude Code login. After Register, provisioning installs the CA + sets HTTPS_PROXY; then hit login."
        columns={[
          { key: "id", label: "id" },
          { key: "label", label: "label" },
          { key: "host", label: "host" },
          { key: "sshUser", label: "user" },
          { key: "status", label: "status" },
          { key: "hasCredential", label: "cred", render: (v) => (v ? "yes" : "—") },
          { key: "httpsProxyUrl", label: "HTTPS_PROXY" },
        ]}
        load={() => request("GET", "/machine", { token })}
        createFields={[
          { name: "host", label: "host (ip/hostname)" },
          { name: "sshUser", label: "ssh user", optional: true, placeholder: "root" },
          { name: "sshPort", label: "ssh port", type: "number", optional: true, placeholder: "22" },
          { name: "label", label: "label", optional: true },
        ]}
        createLabel="Register"
        onCreate={(b) => request("POST", "/machine", { token, body: b })}
        actions={[
          {
            label: "login",
            run: (r) => request("POST", `/machine/${r.id}/login`, { token, body: {} }),
          },
          {
            label: "reprovision",
            run: (r) => request("POST", `/machine/${r.id}/reprovision`, { token, body: {} }),
          },
          { label: "view", run: (r) => request("GET", `/machine/${r.id}`, { token }) },
          {
            label: "delete",
            confirm: "delete machine?",
            run: (r) => request("DELETE", `/machine/${r.id}`, { token }),
          },
        ]}
      />
    );
  return <UsagePanel token={token} />;
}

function SetupPanel({ token }: { token: string }) {
  const [pubkey, setPubkey] = useState("");
  const [ca, setCa] = useState("");
  const [err, setErr] = useState("");
  useEffect(() => {
    request("GET", "/provisioning/ssh-pubkey", { token })
      .then((r) => setPubkey(r.publicKey))
      .catch((e) => setErr((e as Error).message));
    request("GET", "/provisioning/ca-cert", { token })
      .then((r) => setCa(r.pem))
      .catch(() => {});
  }, [token]);
  return (
    <section className="panel">
      <header>
        <h2>Setup</h2>
      </header>
      <p className="hint">
        Inject this SSH public key into a machine (as the SSH user) before registering it, so CCProxy
        can log in. The CA cert is installed automatically during provisioning.
      </p>
      {err && <div className="msg err">{err}</div>}
      <div className="output">
        <div className="outputhead">
          <span>SSH public key</span>
        </div>
        <pre>{pubkey || "…"}</pre>
      </div>
      <div className="output">
        <div className="outputhead">
          <span>MITM CA certificate</span>
        </div>
        <pre>{ca || "…"}</pre>
      </div>
    </section>
  );
}

// ── Operator ─────────────────────────────────────────────────────────────────────
function OperatorPanels({ tab, token }: { tab: string; token: string }) {
  const [status, setStatus] = useState("awaitingCode");
  if (tab === "Login queue")
    return (
      <ResourcePanel
        title="Login queue"
        description="Open the OAuth URL in a browser sent THROUGH the account's proxy (so the login IP matches the API egress), sign in as that account, then paste the code on the Submit-code tab."
        columns={[
          { key: "id", label: "id" },
          { key: "machineId", label: "machine" },
          { key: "accountEmail", label: "account" },
          { key: "accountProxy", label: "proxy" },
          { key: "accountRemark", label: "remark" },
          { key: "status", label: "status" },
          {
            key: "oauthUrl",
            label: "oauth url",
            render: (v) =>
              v ? (
                <a href={v} target="_blank" rel="noreferrer">
                  open
                </a>
              ) : (
                "—"
              ),
          },
        ]}
        load={() => request("GET", `/login-request?status=${status}`, { token })}
        filters={
          <label>
            <span>status</span>
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              {["preparing", "awaitingCode", "applying", "completed", "failed", "expired", "cancelled"].map(
                (s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ),
              )}
            </select>
          </label>
        }
        actions={[
          { label: "view", run: (r) => request("GET", `/login-request/${r.id}`, { token }) },
          {
            label: "cancel",
            confirm: "cancel this login?",
            run: (r) => request("POST", `/login-request/${r.id}/cancel`, { token, body: {} }),
          },
        ]}
      />
    );
  return (
    <ResourcePanel
      title="Submit login code"
      description="Paste the code#state string from the browser after authenticating. CCProxy generates a fake code, drives Claude Code to accept it, and swaps fake→real on the wire."
      columns={[]}
      load={async () => []}
      createFields={[
        { name: "id", label: "login-request id", type: "number" },
        { name: "codeState", label: "code#state", placeholder: "paste the browser's code#state" },
      ]}
      createLabel="Submit"
      onCreate={(b) =>
        request("POST", `/login-request/${b.id}/code`, {
          token,
          body: { codeState: b.codeState },
        })
      }
    />
  );
}

// ── Usage (shared) ────────────────────────────────────────────────────────────────
function UsagePanel({ token }: { token: string }) {
  return (
    <ResourcePanel
      title="Usage"
      description="Raw metered calls. /usage/summary gives 1-minute buckets for billing."
      columns={[
        { key: "id", label: "id" },
        { key: "tenantId", label: "tenant" },
        { key: "machineId", label: "machine" },
        { key: "model", label: "model" },
        { key: "inputTokens", label: "in" },
        { key: "outputTokens", label: "out" },
        { key: "at", label: "at" },
      ]}
      load={() => request("GET", "/usage", { token })}
    />
  );
}
