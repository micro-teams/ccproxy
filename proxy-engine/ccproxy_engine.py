#!/usr/bin/env python3
"""
CCProxy engine — the MITM data plane.

Productizes the validated experiment (ccproxy-experiment-detailed.md) into a multi-session engine:

  * A MITM proxy on :3128. Each machine points HTTPS_PROXY at it with its own proxy-auth
    (user:pass); the username identifies the session, so one engine serves every machine and keeps
    each machine's fake↔real credential separate.
  * A control API on :9000 the backend drives (register/remove a session, prime a login, read back
    login status). The backend is the control plane; this process holds the real tokens.
  * Per-session egress: the upstream TLS connection to Anthropic is made THROUGH the session's
    account proxy, so a machine's API traffic and its manual login share one egress IP.
  * Usage reporting: metered calls are POSTed to the backend's /internal/usage.

Swaps performed on the wire (per session):
  request  body : fake OAuth code  → real code   (during login token exchange)
  request  auth : fake access token → real token (every API call)
  response body : real tokens       → fake tokens (login token response)

Real tokens NEVER leave this process toward a machine, and are never returned over the control API.
"""

import gzip
import json
import os
import secrets
import select
import socket
import ssl
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ── Configuration (env) ───────────────────────────────────────────────────────
PROXY_PORT = int(os.environ.get("CCPROXY_PROXY_PORT", "3128"))
CONTROL_PORT = int(os.environ.get("CCPROXY_CONTROL_PORT", "9000"))
CONTROL_SECRET = os.environ.get("CCPROXY_ENGINE_SECRET", "")
BACKEND_URL = os.environ.get("CCPROXY_BACKEND_URL", "http://backend:8080").rstrip("/")
CA_CERT = os.environ.get("CCPROXY_CA_CERT", "/keys/ca.crt")
CA_KEY = os.environ.get("CCPROXY_CA_KEY", "/keys/ca.key")
CERTS_DIR = os.environ.get("CCPROXY_CERTS_DIR", "/tmp/ccproxy-certs")
MITM_DOMAINS = {"api.anthropic.com", "platform.claude.com"}
# When set, every MITM'd (decrypted) request/response is mirrored under this directory, per machine.
# Purely a side-channel copy — it never touches the bytes forwarded to the client.
DUMP_DIR = os.environ.get("CCPROXY_DUMP_DIR", "")
# When set, each session's captured real+fake tokens are persisted here so an engine restart does not
# lose them (otherwise every machine would have to log in again). One JSON file per proxyUser.
SESSION_DIR = os.environ.get("CCPROXY_SESSION_DIR", "")

os.makedirs(CERTS_DIR, exist_ok=True)
_log_lock = threading.Lock()


def log(msg):
    with _log_lock:
        print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ── Session registry ──────────────────────────────────────────────────────────
class Session:
    def __init__(self, proxy_password, account_proxy, user=None):
        self.user = user
        self.proxy_password = proxy_password
        self.account_proxy = account_proxy
        self.real_access = None
        self.real_refresh = None
        self.fake_access = None
        self.fake_refresh = None
        self.expires_at = None  # epoch seconds
        # pending login swap: {"realCode","state","fakeCode"}
        self.pending = None


class Registry:
    def __init__(self):
        self._lock = threading.Lock()
        self._by_user = {}

    def put(self, user, proxy_password, account_proxy):
        with self._lock:
            s = self._by_user.get(user)
            if s is None:
                s = Session(proxy_password, account_proxy, user)
                self._by_user[user] = s
            else:
                s.proxy_password = proxy_password
                s.account_proxy = account_proxy
            return s

    def get(self, user):
        with self._lock:
            return self._by_user.get(user)

    def remove(self, user):
        with self._lock:
            self._by_user.pop(user, None)


REGISTRY = Registry()


# ── Session token persistence ──────────────────────────────────────────────────
# Real credentials live in memory, so an engine restart would lose them and force every machine to
# log in again. When SESSION_DIR is set we mirror each session's tokens to disk on capture and reload
# them on startup / on a cache miss, so a restart is transparent. Written synchronously so disk is
# never behind the fake tokens the machine already holds.
_persist_lock = threading.Lock()
_TOKEN_FIELDS = (
    "proxy_password",
    "account_proxy",
    "real_access",
    "real_refresh",
    "fake_access",
    "fake_refresh",
    "expires_at",
)


def persist_session(user, sess):
    if not SESSION_DIR:
        return
    try:
        os.makedirs(SESSION_DIR, exist_ok=True)
        data = {k: getattr(sess, k) for k in _TOKEN_FIELDS}
        p = os.path.join(SESSION_DIR, user + ".json")
        tmp = p + ".tmp"
        with _persist_lock:
            with open(tmp, "w") as f:
                json.dump(data, f)
            os.replace(tmp, p)  # atomic
    except Exception as e:
        log(f"persist session {user} failed: {str(e)[:80]}")


def load_persisted_tokens(user, sess):
    """Overlay disk-persisted tokens onto an existing session (leaves proxy_password/account_proxy as
    provided by the backend, which is authoritative for those). Returns True if tokens were loaded."""
    if not SESSION_DIR:
        return False
    try:
        p = os.path.join(SESSION_DIR, user + ".json")
        if not os.path.exists(p):
            return False
        with open(p) as f:
            d = json.load(f)
        for k in ("real_access", "real_refresh", "fake_access", "fake_refresh", "expires_at"):
            if d.get(k) is not None:
                setattr(sess, k, d[k])
        return sess.real_access is not None
    except Exception as e:
        log(f"load session {user} failed: {str(e)[:80]}")
        return False


def load_all_persisted():
    """On startup, restore every persisted session into the registry so machines keep working after a
    restart without waiting for a backend round-trip."""
    if not SESSION_DIR or not os.path.isdir(SESSION_DIR):
        return
    n = 0
    for fn in os.listdir(SESSION_DIR):
        if not fn.endswith(".json"):
            continue
        user = fn[:-5]
        try:
            with open(os.path.join(SESSION_DIR, fn)) as f:
                d = json.load(f)
            sess = REGISTRY.put(user, d.get("proxy_password", ""), d.get("account_proxy"))
            load_persisted_tokens(user, sess)
            n += 1
        except Exception as e:
            log(f"restore session {user} failed: {str(e)[:80]}")
    if n:
        log(f"restored {n} persisted session(s)")


# ── Certificate generation (per MITM domain, signed by the mounted CA) ─────────
_cert_lock = threading.Lock()


def gen_cert(domain):
    kf = os.path.join(CERTS_DIR, f"{domain}.key")
    cf = os.path.join(CERTS_DIR, f"{domain}.crt")
    with _cert_lock:
        if os.path.exists(kf) and os.path.exists(cf):
            return kf, cf
        csr = os.path.join(CERTS_DIR, f"{domain}.csr")
        # The serial file must live in a writable dir — the CA dir (/keys) is mounted read-only,
        # so -CAcreateserial next to the CA would fail and yield an empty (unloadable) cert.
        srl = os.path.join(CERTS_DIR, "ca.srl")
        os.system(f"openssl genrsa -out {kf} 2048 2>/dev/null")
        os.system(f"openssl req -new -key {kf} -out {csr} -subj '/CN={domain}' 2>/dev/null")
        rc = os.system(
            f"openssl x509 -req -in {csr} -CA {CA_CERT} -CAkey {CA_KEY} "
            f"-CAserial {srl} -CAcreateserial -out {cf} -days 365 2>/dev/null"
        )
        if os.path.exists(csr):
            os.remove(csr)
        if rc != 0 or not os.path.exists(cf) or os.path.getsize(cf) == 0:
            raise RuntimeError(f"failed to sign leaf cert for {domain} (openssl rc={rc})")
        return kf, cf


# ── Upstream connection through the session's account proxy ────────────────────
def upstream_tls(host, port, account_proxy):
    """Open a TLS connection to host:port, tunnelling through account_proxy (http://h:p)."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    if account_proxy:
        p = account_proxy.split("://", 1)[-1]
        phost, pport = p.split(":")
        raw = socket.create_connection((phost, int(pport)), timeout=30)
        raw.sendall(f"CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\n\r\n".encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = raw.recv(4096)
            if not chunk:
                raise ConnectionError("egress proxy closed during CONNECT")
            resp += chunk
        if b" 200 " not in resp.split(b"\r\n", 1)[0]:
            raise ConnectionError(f"egress proxy refused CONNECT: {resp[:80]!r}")
    else:
        raw = socket.create_connection((host, port), timeout=30)
    return ctx.wrap_socket(raw, server_hostname=host)


# ── HTTP framing helpers ───────────────────────────────────────────────────────
def recv_line(s):
    d = b""
    while not d.endswith(b"\r\n"):
        b = s.recv(1)
        if not b:
            return None
        d += b
    return d


def recv_exact(s, n):
    d = b""
    while len(d) < n:
        c = s.recv(n - len(d))
        if not c:
            return None
        d += c
    return d


def parse_headers(s):
    h = {}
    while True:
        line = recv_line(s)
        if line is None:
            return None
        line = line.strip()
        if line == b"":
            break
        i = line.find(b":")
        if i > 0:
            h[line[:i].decode(errors="replace").strip()] = line[i + 1 :].decode(errors="replace").strip()
    return h


def read_body(s, h):
    if h.get("Transfer-Encoding", "").lower() == "chunked":
        body = b""
        while True:
            line = recv_line(s)
            if line is None:
                return None
            line = line.strip()
            if not line:
                continue
            try:
                size = int(line, 16)
            except ValueError:
                return body
            if size == 0:
                recv_line(s)
                break
            chunk = recv_exact(s, size)
            if chunk is None:
                return None
            body += chunk
            recv_line(s)
        return body
    cl = int(h.get("Content-Length", 0))
    return recv_exact(s, cl) if cl > 0 else b""


def maybe_gunzip(body, headers):
    if headers.get("Content-Encoding", "").lower() == "gzip" and body:
        try:
            return gzip.decompress(body)
        except Exception:
            return body
    return body


# ── Swaps ──────────────────────────────────────────────────────────────────────
def swap_request_body(body, sess):
    if not body:
        return body
    text = body.decode(errors="replace")
    changed = False
    if sess.pending and sess.pending["fakeCode"] and sess.pending["fakeCode"] in text:
        text = text.replace(sess.pending["fakeCode"], sess.pending["realCode"])
        changed = True
    if sess.fake_access and sess.real_access and sess.fake_access in text:
        text = text.replace(sess.fake_access, sess.real_access)
        changed = True
    if sess.fake_refresh and sess.real_refresh and sess.fake_refresh in text:
        text = text.replace(sess.fake_refresh, sess.real_refresh)
        changed = True
    return text.encode() if changed else body


def swap_auth_header(headers, sess):
    if not (sess.fake_access and sess.real_access):
        return
    for k in list(headers.keys()):
        if k.lower() == "authorization" and sess.fake_access in headers[k]:
            headers[k] = headers[k].replace(sess.fake_access, sess.real_access)


def swap_response_body(body, sess):
    """Capture real tokens on the oauth/token response; return the machine fake tokens."""
    if not body:
        return body
    text = body.decode(errors="replace")
    try:
        obj = json.loads(text)
    except Exception:
        obj = None
    if isinstance(obj, dict) and "access_token" in obj:
        sess.real_access = obj.get("access_token")
        sess.real_refresh = obj.get("refresh_token")
        sess.fake_access = "sk-ant-oat01-" + secrets.token_hex(32)
        sess.fake_refresh = "sk-ant-ort01-" + secrets.token_hex(32)
        if obj.get("expires_in"):
            sess.expires_at = int(time.time()) + int(obj["expires_in"])
        sess.pending = None
        obj["access_token"] = sess.fake_access
        if sess.real_refresh:
            obj["refresh_token"] = sess.fake_refresh
        # Persist before handing the fake tokens back, so disk is never behind the machine.
        if sess.user:
            persist_session(sess.user, sess)
        log("captured real tokens; handed fake tokens to machine")
        return json.dumps(obj).encode()
    # Steady-state: never leak a real token that might echo back.
    changed = False
    if sess.real_access and sess.fake_access and sess.real_access in text:
        text = text.replace(sess.real_access, sess.fake_access)
        changed = True
    if sess.real_refresh and sess.fake_refresh and sess.real_refresh in text:
        text = text.replace(sess.real_refresh, sess.fake_refresh)
        changed = True
    return text.encode() if changed else body


def report_usage(user, path, resp_body):
    """Best-effort: extract token usage from a /v1/messages response and report it."""
    if "/v1/messages" not in path:
        return
    try:
        model = None
        inp = out = cr = cw = 0
        for line in resp_body.decode(errors="replace").splitlines():
            line = line.strip()
            if not line.startswith("data:"):
                continue
            try:
                ev = json.loads(line[5:].strip())
            except Exception:
                continue
            msg = ev.get("message") or ev
            model = model or (msg.get("model") if isinstance(msg, dict) else None)
            usage = (msg.get("usage") if isinstance(msg, dict) else None) or ev.get("usage")
            if isinstance(usage, dict):
                inp = usage.get("input_tokens", inp) or inp
                out = usage.get("output_tokens", out) or out
                cr = usage.get("cache_read_input_tokens", cr) or cr
                cw = usage.get("cache_creation_input_tokens", cw) or cw
        if model or inp or out:
            payload = {
                "proxyUser": user,
                "model": model or "unknown",
                "inputTokens": int(inp),
                "outputTokens": int(out),
                "cacheReadTokens": int(cr),
                "cacheWriteTokens": int(cw),
            }
            req = urllib.request.Request(
                f"{BACKEND_URL}/internal/usage",
                data=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json", "X-Engine-Secret": CONTROL_SECRET},
                method="POST",
            )
            urllib.request.urlopen(req, timeout=5).read()
    except Exception as e:
        log(f"usage report failed: {e}")


# ── Traffic dump (optional, side-channel only) ───────────────────────────────────
def dump_exchange(user, method, path, req_headers, req_body, status, resp_headers, resp_body):
    """Mirror one decrypted request/response to DUMP_DIR/<machine>/. Best-effort and always run in a
    daemon thread, so a slow or failing disk write can never affect what the client receives. Records
    the client's view (fake credential), so real tokens are never written to disk."""
    if not DUMP_DIR:
        return
    try:
        d = os.path.join(DUMP_DIR, user or "unknown")
        os.makedirs(d, exist_ok=True)
        ts = time.strftime("%Y%m%dT%H%M%S", time.gmtime())
        seq = f"{int(time.time() * 1000) % 1000:03d}"
        safe = "".join(c if c.isalnum() else "_" for c in path)[:48].strip("_") or "root"
        fn = os.path.join(d, f"{ts}_{seq}_{method}_{safe}.http")
        with open(fn, "wb") as f:
            f.write(f"{method} {path} HTTP/1.1\r\n".encode())
            for k, v in req_headers.items():
                f.write(f"{k}: {v}\r\n".encode())
            f.write(b"\r\n")
            f.write(req_body or b"")
            f.write(b"\r\n\r\n===== RESPONSE =====\r\n")
            f.write(status or b"")
            for k, v in resp_headers.items():
                f.write(f"{k}: {v}\r\n".encode())
            f.write(b"\r\n")
            f.write(resp_body or b"")
    except Exception as e:
        log(f"dump failed: {str(e)[:80]}")


# ── MITM forwarding ────────────────────────────────────────────────────────────
def forward(upstream, method, path, headers, body, sess, user):
    # Capture the client's view (pre-swap: fake credential) for the optional traffic dump.
    dump_req_headers = dict(headers) if DUMP_DIR else None
    dump_req_body = body if DUMP_DIR else None
    body = swap_request_body(body, sess)
    swap_auth_header(headers, sess)
    if body:
        headers["Content-Length"] = str(len(body))
    req = f"{method} {path} HTTP/1.1\r\n".encode()
    for k, v in headers.items():
        if k.lower() == "content-length" and not body:
            continue
        req += f"{k}: {v}\r\n".encode()
    req += b"\r\n" + (body or b"")
    upstream.sendall(req)

    status = recv_line(upstream)
    if not status:
        return None
    rh = parse_headers(upstream)
    if rh is None:
        return None
    # Snapshot the upstream response headers verbatim (before we strip encoding/rewrite length below)
    # so the dump records exactly what Anthropic sent — incl. every anthropic-ratelimit-* header, for
    # quota-consumption analysis.
    dump_resp_headers = dict(rh) if DUMP_DIR else None
    rb = read_body(upstream, rh)
    if rh.get("Transfer-Encoding", "").lower() == "chunked":
        rh.pop("Transfer-Encoding", None)
    raw = maybe_gunzip(rb, rh)
    if raw is not rb:
        rh.pop("Content-Encoding", None)
    swapped = swap_response_body(raw, sess)
    rh["Content-Length"] = str(len(swapped))
    threading.Thread(target=report_usage, args=(user, path, raw), daemon=True).start()
    if DUMP_DIR:
        threading.Thread(
            target=dump_exchange,
            args=(user, method, path, dump_req_headers, dump_req_body, status, dump_resp_headers, swapped),
            daemon=True,
        ).start()
    out = status
    for k, v in rh.items():
        out += f"{k}: {v}\r\n".encode()
    out += b"\r\n" + swapped
    return out


def handle_mitm(client_tls, host, port, sess, user):
    try:
        up = upstream_tls(host, port, sess.account_proxy)
    except Exception as e:
        log(f"upstream TLS to {host} failed: {e}")
        return
    try:
        while True:
            line = recv_line(client_tls)
            if line is None:
                break
            parts = line.strip().split(b" ")
            if len(parts) < 3:
                break
            method, path = parts[0].decode(), parts[1].decode()
            h = parse_headers(client_tls)
            if h is None:
                break
            b = read_body(client_tls, h)
            resp = forward(up, method, path, h, b, sess, user)
            if not resp:
                break
            client_tls.sendall(resp)
            if h.get("Connection", "").lower() == "close":
                break
    except Exception as e:
        log(f"mitm {host}: {str(e)[:100]}")
    finally:
        try:
            up.close()
        except Exception:
            pass


def tunnel(a, b):
    a.setblocking(False)
    b.setblocking(False)
    pipes = {a: b, b: a}
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 60)
            if not r:
                break
            for s in r:
                data = s.recv(8192)
                if not data:
                    return
                pipes[s].sendall(data)
    except Exception:
        pass
    finally:
        for s in (a, b):
            try:
                s.close()
            except Exception:
                pass


def fetch_session(user):
    """Self-heal: the session registry is in memory, so a restart loses it. On a miss, pull this
    machine's session (proxyPassword + account egress proxy) from the backend — the DB is the source
    of truth — and cache it. Returns the Session, or None if the backend doesn't know this user."""
    try:
        req = urllib.request.Request(
            f"{BACKEND_URL}/internal/session?proxyUser={urllib.parse.quote(user)}",
            headers={"X-Engine-Secret": CONTROL_SECRET},
        )
        data = json.loads(urllib.request.urlopen(req, timeout=5).read())
        sess = REGISTRY.put(user, data["proxyPassword"], data["accountProxy"])
        # Restore captured tokens from disk if we have them, so a logged-in machine keeps working
        # across an engine restart without re-login.
        load_persisted_tokens(user, sess)
        return sess
    except Exception as e:
        log(f"session fetch for {user} failed: {str(e)[:80]}")
        return None


def parse_proxy_auth(headers):
    """Return (user, password) from a Proxy-Authorization: Basic header, or (None, None)."""
    import base64

    for k, v in headers.items():
        if k.lower() == "proxy-authorization":
            try:
                scheme, blob = v.split(" ", 1)
                if scheme.lower() == "basic":
                    user, _, pw = base64.b64decode(blob).decode().partition(":")
                    return user, pw
            except Exception:
                return None, None
    return None, None


def handle_client(cs):
    try:
        line = recv_line(cs)
        if not line:
            return
        parts = line.strip().split(b" ")
        if len(parts) < 3 or parts[0] != b"CONNECT":
            return
        target = parts[1].decode()
        headers = parse_headers(cs)
        if headers is None:
            return
        host, _, port = target.partition(":")
        port = int(port or 443)
        user, pw = parse_proxy_auth(headers)
        sess = REGISTRY.get(user) if user else None
        if sess is None and user:
            # Cache miss (e.g. after an engine restart) — rebuild the session from the backend so
            # machines keep working without a manual reprovision.
            sess = fetch_session(user)
        if host in MITM_DOMAINS and sess and sess.proxy_password == pw:
            cs.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            kf, cf = gen_cert(host)
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(cf, kf)
            client_tls = ctx.wrap_socket(cs, server_side=True)
            client_tls.settimeout(120)
            handle_mitm(client_tls, host, port, sess, user)
        else:
            # Non-MITM domains (or unauthenticated) — plain tunnel through the account proxy if we
            # know the session, else direct.
            cs.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            proxy = sess.account_proxy if sess else None
            if proxy:
                p = proxy.split("://", 1)[-1]
                ph, pp = p.split(":")
                up = socket.create_connection((ph, int(pp)), timeout=30)
                up.sendall(f"CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\n\r\n".encode())
                resp = b""
                while b"\r\n\r\n" not in resp:
                    chunk = up.recv(4096)
                    if not chunk:
                        return
                    resp += chunk
            else:
                up = socket.create_connection((host, port), timeout=30)
            tunnel(cs, up)
    except Exception as e:
        log(f"client: {str(e)[:100]}")
    finally:
        try:
            cs.close()
        except Exception:
            pass


def run_proxy():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", PROXY_PORT))
    sock.listen(128)
    log(f"MITM proxy listening on :{PROXY_PORT}")
    while True:
        c, _ = sock.accept()
        threading.Thread(target=handle_client, args=(c,), daemon=True).start()


# ── Control API (:9000) ────────────────────────────────────────────────────────
class ControlHandler(BaseHTTPRequestHandler):
    def _auth_ok(self):
        if not CONTROL_SECRET:
            return True
        return self.headers.get("X-Engine-Secret") == CONTROL_SECRET

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length", 0))
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/health":
            return self._json(200, {"status": "ok"})
        if not self._auth_ok():
            return self._json(403, {"error": "forbidden"})
        # /sessions/{user}/login
        parts = self.path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "sessions" and parts[2] == "login":
            sess = REGISTRY.get(parts[1])
            if not sess:
                return self._json(404, {"error": "no session"})
            return self._json(200, {"hasCredential": bool(sess.fake_access), "expiresAt": sess.expires_at})
        return self._json(404, {"error": "not found"})

    def do_PUT(self):
        if not self._auth_ok():
            return self._json(403, {"error": "forbidden"})
        parts = self.path.strip("/").split("/")
        if len(parts) == 2 and parts[0] == "sessions":
            body = self._body()
            REGISTRY.put(parts[1], body.get("proxyPassword", ""), body.get("accountProxy"))
            return self._json(200, {"ok": True})
        return self._json(404, {"error": "not found"})

    def do_DELETE(self):
        if not self._auth_ok():
            return self._json(403, {"error": "forbidden"})
        parts = self.path.strip("/").split("/")
        if len(parts) == 2 and parts[0] == "sessions":
            REGISTRY.remove(parts[1])
            return self._json(200, {"ok": True})
        return self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._auth_ok():
            return self._json(403, {"error": "forbidden"})
        parts = self.path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "sessions" and parts[2] == "login":
            sess = REGISTRY.get(parts[1])
            if not sess:
                return self._json(404, {"error": "no session"})
            body = self._body()
            sess.pending = {
                "realCode": body.get("realCode"),
                "state": body.get("state"),
                "fakeCode": body.get("fakeCode"),
            }
            return self._json(200, {"ok": True})
        return self._json(404, {"error": "not found"})


def run_control():
    server = ThreadingHTTPServer(("0.0.0.0", CONTROL_PORT), ControlHandler)
    log(f"control API listening on :{CONTROL_PORT}")
    server.serve_forever()


def main():
    load_all_persisted()
    threading.Thread(target=run_control, daemon=True).start()
    run_proxy()


if __name__ == "__main__":
    main()
