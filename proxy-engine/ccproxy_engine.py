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
  refresh grant : answered locally with the machine's stable fake pair; the real chain is
                  refreshed upstream at most single-flight, only when it nears expiry (#67)

A machine's fake pair is minted once and then STABLE: a refresh never invalidates it, so every
claude process on the machine stays valid however many of them refresh concurrently — the machine
(DELETE /machine/{id}) is the one revocation unit. Real tokens NEVER leave this process toward a
machine, and are never returned over the control API.
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
# Idle keep-alive management for a decrypted MITM connection. CLIENT_IDLE_TIMEOUT is how long the
# connection may sit BETWEEN requests before we reap it — kept well above the client's own keep-alive
# reuse window so the proxy is never the shortest idle timeout on the path. (When it was, the client
# would reuse a pooled socket we had already closed and the first request failed — the "connection
# failed, works on retry" symptom.) Reading an already-started request uses CLIENT_ACTIVE_TIMEOUT.
CLIENT_IDLE_TIMEOUT = int(os.environ.get("CCPROXY_CLIENT_IDLE_TIMEOUT", "900"))
CLIENT_ACTIVE_TIMEOUT = int(os.environ.get("CCPROXY_CLIENT_ACTIVE_TIMEOUT", "120"))
# Read timeout on the upstream (Anthropic) socket once connected — generous, so a long streamed
# generation with a quiet stretch isn't mistaken for a dead connection. The connect itself stays 30s.
UPSTREAM_TIMEOUT = int(os.environ.get("CCPROXY_UPSTREAM_TIMEOUT", "300"))
# TCP keepalive on the long-lived sockets (both legs): probes keep an idle connection's NAT/firewall
# mapping warm and surface a silently dropped peer proactively, instead of failing on the next use.
TCP_KEEPIDLE = int(os.environ.get("CCPROXY_TCP_KEEPIDLE", "60"))
# A refresh grant arriving while the real access token still has more than this many seconds of
# life is answered locally without touching upstream. Must stay ABOVE the claude CLI's own
# proactive-refresh margin (minutes), or a locally-answered client would immediately consider its
# new expiry stale and refresh-loop.
REFRESH_MARGIN = int(os.environ.get("CCPROXY_REFRESH_MARGIN", "3600"))
# After a failed upstream refresh, don't retry upstream for this many seconds; refreshes arriving
# meanwhile are answered locally so a transient Anthropic failure can't stampede the real chain.
REFRESH_BACKOFF = int(os.environ.get("CCPROXY_REFRESH_BACKOFF", "30"))
# When set, every MITM'd (decrypted) request/response is mirrored under this directory, per machine.
# Purely a side-channel copy — it never touches the bytes forwarded to the client.
DUMP_DIR = os.environ.get("CCPROXY_DUMP_DIR", "")

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
        self.expires_at = None  # epoch seconds — the REAL access token's expiry
        # pending login swap: {"realCode","state","fakeCode"}
        self.pending = None
        # Single-flight guard for the real-chain refresh (#67): one upstream attempt at a time,
        # concurrent refreshers block briefly and then get the fresh state answered locally.
        self.refresh_lock = threading.Lock()
        self.last_refresh_fail = 0.0
        # Non-token fields of the last real token response (scope etc.), echoed into locally
        # synthesized refresh answers so the client sees exactly what the real chain granted.
        # In-memory only; after a restart the lab-validated default in local_refresh_response
        # covers until the next upstream capture.
        self.token_extras = None


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


# ── Session credential store (Postgres, via the backend) ───────────────────────
# Real credentials live in memory; an engine restart loses the registry. The backend's Postgres
# `credential` table is the durable source of truth: the engine writes each session's tokens there on
# capture, reloads them all on startup, and lazily re-fetches a single session on a cache miss. (The
# proxy-engine `depends_on` the backend being healthy, so the DB is always reachable at engine start.)


def persist_session(user, sess):
    """Write a session's captured/injected tokens to the backend DB. Synchronous on capture so the
    store is never behind the fake tokens the machine already holds; a failure only logs — the
    in-memory session keeps the machine working until the next write."""
    if not BACKEND_URL or not CONTROL_SECRET:
        return
    try:
        payload = {
            "proxyUser": user,
            "proxyPassword": sess.proxy_password,
            "accountProxy": sess.account_proxy,
            "realAccess": sess.real_access,
            "realRefresh": sess.real_refresh,
            "fakeAccess": sess.fake_access,
            "fakeRefresh": sess.fake_refresh,
            "expiresAt": sess.expires_at,
        }
        req = urllib.request.Request(
            f"{BACKEND_URL}/internal/credential/session",
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "X-Engine-Secret": CONTROL_SECRET},
            method="POST",
        )
        urllib.request.urlopen(req, timeout=5).read()
    except Exception as e:
        log(f"persist session {user} failed: {str(e)[:80]}")


def _apply_db_tokens(sess, row):
    """Overlay non-null token fields from a DB credential row onto a session (proxy fields are left
    as the caller set them)."""
    for src, attr in (
        ("realAccess", "real_access"),
        ("realRefresh", "real_refresh"),
        ("fakeAccess", "fake_access"),
        ("fakeRefresh", "fake_refresh"),
        ("expiresAt", "expires_at"),
    ):
        if row.get(src) is not None:
            setattr(sess, attr, row[src])


def load_db_tokens(user, sess):
    """Cache-miss self-heal: fetch one session's tokens from the DB and overlay them. Returns True if
    the machine has a captured credential; 404 = registered-but-never-logged-in (no tokens yet)."""
    if not BACKEND_URL or not CONTROL_SECRET:
        return False
    try:
        req = urllib.request.Request(
            f"{BACKEND_URL}/internal/credential/session/{urllib.parse.quote(user)}",
            headers={"X-Engine-Secret": CONTROL_SECRET},
        )
        row = json.loads(urllib.request.urlopen(req, timeout=5).read())
        _apply_db_tokens(sess, row)
        return sess.real_access is not None
    except Exception as e:
        if getattr(e, "code", None) == 404:
            return False
        log(f"db token fetch for {user} failed: {str(e)[:80]}")
        return False


def load_all_from_db():
    """On startup, load every session from the backend DB into the registry so machines keep working
    across a restart without re-login. The DB is the source of truth."""
    if not BACKEND_URL or not CONTROL_SECRET:
        return
    try:
        req = urllib.request.Request(
            f"{BACKEND_URL}/internal/credential/sessions",
            headers={"X-Engine-Secret": CONTROL_SECRET},
        )
        rows = json.loads(urllib.request.urlopen(req, timeout=10).read())
    except Exception as e:
        log(f"DB session load failed: {str(e)[:80]}")
        return
    n = 0
    for r in rows:
        user = r.get("proxyUser")
        if not user:
            continue
        sess = REGISTRY.put(user, r.get("proxyPassword") or "", r.get("accountProxy"))
        _apply_db_tokens(sess, r)
        n += 1
    log(f"loaded {n} session(s) from DB")


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
def set_tcp_keepalive(sock):
    """Enable TCP keepalive on a long-lived socket: keeps an idle connection's NAT/firewall mapping
    alive and surfaces a dead peer proactively rather than on the next write. Options past
    SO_KEEPALIVE are Linux-specific and applied best-effort."""
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        for name, val in (("TCP_KEEPIDLE", TCP_KEEPIDLE), ("TCP_KEEPINTVL", 15), ("TCP_KEEPCNT", 4)):
            if hasattr(socket, name):
                sock.setsockopt(socket.IPPROTO_TCP, getattr(socket, name), val)
    except OSError:
        pass


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
    # Long-lived and reused across a client's requests: keep it warm and give reads room for a slow
    # stream, rather than the 30s connect timeout that create_connection leaves on the socket.
    set_tcp_keepalive(raw)
    raw.settimeout(UPSTREAM_TIMEOUT)
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


def capture_real_tokens(sess, obj):
    """Take the real pair (and expiry + extra fields) from a real token response. The fake pair is
    minted only if the machine doesn't have one yet — an existing pair is STABLE (#67), so tokens
    already in holders' hands survive every rotation and re-login of the real chain."""
    sess.real_access = obj.get("access_token")
    # A refresh response may omit refresh_token (= keep using the old one); never null it out.
    sess.real_refresh = obj.get("refresh_token") or sess.real_refresh
    if not sess.fake_access:
        sess.fake_access = "sk-ant-oat01-" + secrets.token_hex(32)
    if not sess.fake_refresh and sess.real_refresh:
        sess.fake_refresh = "sk-ant-ort01-" + secrets.token_hex(32)
    if obj.get("expires_in"):
        sess.expires_at = int(time.time()) + int(obj["expires_in"])
    sess.token_extras = {
        k: v
        for k, v in obj.items()
        if k not in ("access_token", "refresh_token", "expires_in")
    }
    sess.pending = None
    # Persist before handing the fake tokens back, so disk is never behind the machine.
    if sess.user:
        persist_session(sess.user, sess)


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
        capture_real_tokens(sess, obj)
        obj["access_token"] = sess.fake_access
        if sess.real_refresh and sess.fake_refresh:
            obj["refresh_token"] = sess.fake_refresh
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
def send_request(upstream, method, path, headers, body):
    if body:
        headers["Content-Length"] = str(len(body))
    req = f"{method} {path} HTTP/1.1\r\n".encode()
    for k, v in headers.items():
        if k.lower() == "content-length" and not body:
            continue
        req += f"{k}: {v}\r\n".encode()
    req += b"\r\n" + (body or b"")
    upstream.sendall(req)


def read_response(upstream):
    """Read one upstream response; returns (status_line, headers, body) with chunking and gzip
    normalized away, or None on a dead connection."""
    status = recv_line(upstream)
    if not status:
        return None
    rh = parse_headers(upstream)
    if rh is None:
        return None
    rb = read_body(upstream, rh)
    if rh.get("Transfer-Encoding", "").lower() == "chunked":
        rh.pop("Transfer-Encoding", None)
    raw = maybe_gunzip(rb, rh)
    if raw is not rb:
        rh.pop("Content-Encoding", None)
    return status, rh, raw


def assemble_response(status, rh, body):
    rh["Content-Length"] = str(len(body))
    out = status
    for k, v in rh.items():
        out += f"{k}: {v}\r\n".encode()
    out += b"\r\n" + body
    return out


# ── Refresh absorption (#67) ───────────────────────────────────────────────────
def parse_refresh_grant(body, sess):
    """True iff this request is the machine's own token refresh: a refresh_token grant carrying
    this machine's issued fake refresh token. Keyed on the BODY, not the URL, so it holds across
    CLI versions and token-endpoint hosts. Tries JSON first, then form encoding."""
    if not (body and sess.fake_refresh):
        return False
    text = body.decode(errors="replace")
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            return (
                obj.get("grant_type") == "refresh_token"
                and obj.get("refresh_token") == sess.fake_refresh
            )
    except Exception:
        pass
    q = urllib.parse.parse_qs(text)
    return q.get("grant_type") == ["refresh_token"] and q.get("refresh_token") == [
        sess.fake_refresh
    ]


def local_refresh_response(sess):
    """A locally synthesized token response carrying the machine's stable fake pair, as a
    (status, headers, body) triple. Non-token fields mirror the last real grant when we hold it;
    the fallback shape matches the real endpoint's and was validated against claude v2.1.233."""
    expires_in = 60
    if sess.expires_at:
        expires_in = max(60, int(sess.expires_at - time.time()))
    obj = dict(
        sess.token_extras or {"token_type": "Bearer", "scope": "user:inference user:profile"}
    )
    obj["access_token"] = sess.fake_access
    if sess.fake_refresh:
        obj["refresh_token"] = sess.fake_refresh
    obj["expires_in"] = expires_in
    return (
        b"HTTP/1.1 200 OK\r\n",
        {"Content-Type": "application/json"},
        json.dumps(obj).encode(),
    )


def absorb_refresh(get_upstream, method, path, headers, body, sess, user):
    """Answer a machine's refresh with its stable fake pair. The pair never rotates, so no holder
    is ever stranded by a sibling's refresh; the REAL chain is refreshed upstream only when it
    nears expiry, single-flight per session however many holders ask at once. Returns a
    (status, headers, body) triple."""
    with sess.refresh_lock:
        now = time.time()
        if sess.expires_at and sess.expires_at - now > REFRESH_MARGIN:
            log(f"{user}: absorbed refresh (real chain fresh for {int(sess.expires_at - now)}s)")
            return local_refresh_response(sess)
        if now - sess.last_refresh_fail < REFRESH_BACKOFF:
            log(f"{user}: absorbed refresh (upstream refresh in backoff)")
            return local_refresh_response(sess)
        try:
            upstream = get_upstream()
            swap_auth_header(headers, sess)
            send_request(upstream, method, path, headers, swap_request_body(body, sess))
            resp = read_response(upstream)
        except Exception as e:
            resp = None
            log(f"{user}: real refresh transport failed: {str(e)[:80]}")
        if resp is None:
            # Transient: the real chain may well still be valid — keep the machine on it and let
            # the client's own retry find upstream recovered.
            sess.last_refresh_fail = time.time()
            return local_refresh_response(sess)
        status, rh, raw = resp
        try:
            obj = json.loads(raw.decode(errors="replace"))
        except Exception:
            obj = None
        if isinstance(obj, dict) and "access_token" in obj:
            capture_real_tokens(sess, obj)
            log(f"{user}: real chain refreshed; fake pair unchanged")
            return local_refresh_response(sess)
        # A definitive upstream refusal (e.g. 400 invalid_grant: the real chain is dead and needs
        # a human re-login) passes through honestly — after the leak-safety swap.
        sess.last_refresh_fail = time.time()
        log(f"{user}: real refresh refused upstream: {status.decode(errors='replace').strip()}")
        return status, rh, swap_response_body(raw, sess)


def forward(get_upstream, method, path, headers, body, sess, user):
    # Capture the client's view (pre-swap: fake credential) for the optional traffic dump.
    dump_req_headers = dict(headers) if DUMP_DIR else None
    dump_req_body = body if DUMP_DIR else None
    if parse_refresh_grant(body, sess):
        status, rh, out = absorb_refresh(get_upstream, method, path, headers, body, sess, user)
    else:
        body = swap_request_body(body, sess)
        swap_auth_header(headers, sess)
        upstream = get_upstream()
        send_request(upstream, method, path, headers, body)
        resp = read_response(upstream)
        if resp is None:
            return None
        status, rh, raw = resp
        out = swap_response_body(raw, sess)
        threading.Thread(target=report_usage, args=(user, path, raw), daemon=True).start()
    if DUMP_DIR:
        threading.Thread(
            target=dump_exchange,
            args=(user, method, path, dump_req_headers, dump_req_body, status, dict(rh), out),
            daemon=True,
        ).start()
    return assemble_response(status, rh, out)


def handle_mitm(client_tls, host, port, sess, user):
    # The upstream connection opens lazily, on the first request that actually needs forwarding —
    # an absorbed refresh completes with no upstream dependency at all.
    up_box = []

    def get_upstream():
        if not up_box:
            up_box.append(upstream_tls(host, port, sess.account_proxy))
        return up_box[0]

    try:
        while True:
            # Waiting for the next request on a kept-alive connection: allow a long idle gap so we
            # never reap a connection the client still considers reusable.
            client_tls.settimeout(CLIENT_IDLE_TIMEOUT)
            line = recv_line(client_tls)
            if line is None:
                break
            parts = line.strip().split(b" ")
            if len(parts) < 3:
                break
            # A request has started; a stalled mid-request read shouldn't hold the connection open
            # for the full idle window.
            client_tls.settimeout(CLIENT_ACTIVE_TIMEOUT)
            method, path = parts[0].decode(), parts[1].decode()
            h = parse_headers(client_tls)
            if h is None:
                break
            b = read_body(client_tls, h)
            resp = forward(get_upstream, method, path, h, b, sess, user)
            if not resp:
                break
            client_tls.sendall(resp)
            if h.get("Connection", "").lower() == "close":
                break
    except Exception as e:
        log(f"mitm {host}: {str(e)[:100]}")
    finally:
        for s in up_box:
            try:
                s.close()
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
        # Overlay this machine's captured tokens from the DB, so a logged-in machine keeps working
        # across an engine restart without re-login.
        load_db_tokens(user, sess)
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


def forward_plain_http(cs, parts):
    """Forward a plain-HTTP proxy request (absolute-form target, e.g. Claude Code -> an http://
    gateway like newapi) straight to its origin over the engine's OWN default network, and relay the
    response back. Anthropic is always HTTPS (CONNECT), so a non-CONNECT request is never something we
    MITM or meter — it just needs to pass through instead of being dropped. We only rewrite the
    request-target to origin-form; the still-buffered headers + body (and the response) are then
    relayed verbatim by tunnel()."""
    method, target, version = parts[0], parts[1].decode("latin-1"), parts[2]
    u = urllib.parse.urlsplit(target)
    if u.scheme != "http" or not u.hostname:
        return
    origin = u.path or "/"
    if u.query:
        origin += "?" + u.query
    up = socket.create_connection((u.hostname, u.port or 80), timeout=30)
    up.sendall(method + b" " + origin.encode("latin-1") + b" " + version + b"\r\n")
    tunnel(cs, up)


def handle_client(cs):
    try:
        # The machine's connection is long-lived (keep-alive) whether MITM'd or tunnelled — keep it
        # warm so an idle gap doesn't get its NAT mapping reaped.
        set_tcp_keepalive(cs)
        line = recv_line(cs)
        if not line:
            return
        parts = line.strip().split(b" ")
        if len(parts) < 3:
            return
        if parts[0] != b"CONNECT":
            # A non-CONNECT request is a plain-HTTP forward-proxy request; pass it through direct
            # instead of dropping the connection (which the client sees as a reset).
            forward_plain_http(cs, parts)
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
            # Idle/active timeouts are managed per-request inside handle_mitm.
            handle_mitm(client_tls, host, port, sess, user)
        else:
            # Anything we don't MITM (non-Anthropic domains, or unauthenticated) tunnels DIRECT — it
            # must NOT go through the session's account egress proxy. Only the machine's Anthropic API
            # traffic uses that egress (for IP consistency with the manual login); routing e.g. a
            # third-party gateway (newapi) through it would break connectivity to hosts that egress
            # can't reach, and that egress is a scarce, bandwidth-limited resource meant only for the
            # precise MITM path.
            cs.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            up = socket.create_connection((host, port), timeout=30)
            set_tcp_keepalive(up)
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
        # /sessions/{user}/credential — inject a ready-made real OAuth token directly (the
        # setup-token path), skipping the interactive /login code exchange. Mints a fresh fake
        # token in the same shape the exchange would have, and returns it so the backend can write
        # it into the machine's CLAUDE_CODE_OAUTH_TOKEN. A setup-token has no refresh_token; that is
        # tolerated everywhere (all refresh swaps are guarded by sess.*_refresh being truthy).
        if len(parts) == 3 and parts[0] == "sessions" and parts[2] == "credential":
            sess = REGISTRY.get(parts[1])
            if not sess:
                return self._json(404, {"error": "no session"})
            body = self._body()
            access = body.get("accessToken")
            if not access:
                return self._json(400, {"error": "accessToken required"})
            sess.real_access = access
            sess.real_refresh = body.get("refreshToken")
            # Mint-if-absent, like every capture path: an existing fake pair is stable (#67), so
            # re-injecting a credential never invalidates tokens already in holders' hands.
            if not sess.fake_access:
                sess.fake_access = "sk-ant-oat01-" + secrets.token_hex(32)
            if not sess.fake_refresh and sess.real_refresh:
                sess.fake_refresh = "sk-ant-ort01-" + secrets.token_hex(32)
            sess.expires_at = body.get("expiresAt")
            sess.pending = None
            if sess.user:
                persist_session(sess.user, sess)
            return self._json(200, {"ok": True, "fakeAccess": sess.fake_access})
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
    load_all_from_db()  # restore the session registry from the DB (the source of truth)
    threading.Thread(target=run_control, daemon=True).start()
    run_proxy()


if __name__ == "__main__":
    main()
