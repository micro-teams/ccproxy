#!/usr/bin/env python3
"""Live test of the shared-quota model throttle (BLOCKED_MODEL_FAMILIES / sniff_and_gate_model).
Boots the real engine against a mock Anthropic egress that just counts connections and echoes a
minimal SSE turn, and asserts:

  - a blocked-family model (opus) is rejected 400 invalid_request_error mentioning "sonnet", and the
    upstream is NEVER CONTACTED — a blocked call costs nothing;
  - an allowed model (sonnet) is forwarded normally, and a body BIGGER than the sniff cap still
    arrives upstream byte-for-byte intact (exercises the peek-prefix + relay-the-rest path, not just
    the whole-body-fits-in-the-peek case);
  - when the model can't be found within the peeked prefix (pushed past it by padding), the gate
    FAILS OPEN — forwards rather than guessing;
  - a chunked-encoded body bypasses the gate entirely (documented limitation) rather than hanging or
    misbehaving;
  - the /config control endpoint toggles the block list live, without a restart.

Stdlib + openssl CLI only. Run: python3 proxy-engine/test_model_gate.py
"""

import base64
import hashlib
import json
import os
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = tempfile.mkdtemp(prefix="ccproxy-gate-test-")
SECRET = "test-secret"

UPSTREAM = {"connections": 0, "last_body_sha": None}
UPSTREAM_LOCK = threading.Lock()


def sh(cmd):
    subprocess.run(cmd, shell=True, check=True, capture_output=True)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def read_until_blank(sock):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            return data
        data += chunk
    return data


def mock_egress(port, certfile, keyfile):
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(16)

    def one(conn):
        try:
            read_until_blank(conn)  # CONNECT
            conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile, keyfile)
            tls = ctx.wrap_socket(conn, server_side=True)
            tls.settimeout(30)
            with UPSTREAM_LOCK:
                UPSTREAM["connections"] += 1
            data = read_until_blank(tls)
            head, _, rest = data.partition(b"\r\n\r\n")
            n = 0
            for line in head.decode(errors="replace").split("\r\n"):
                if line.lower().startswith("content-length:"):
                    n = int(line.split(":", 1)[1])
            body = bytearray(rest)
            while len(body) < n:
                c = tls.recv(65536)
                if not c:
                    break
                body += c
            with UPSTREAM_LOCK:
                UPSTREAM["last_body_sha"] = hashlib.sha256(bytes(body)).hexdigest()
            payload = ("data: " + json.dumps({"type": "message_stop"}) + "\n\n").encode()
            tls.sendall(
                b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                b"Transfer-Encoding: chunked\r\n\r\n"
                + f"{len(payload):x}\r\n".encode() + payload + b"\r\n0\r\n\r\n"
            )
            tls.close()
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass

    while True:
        c, _ = srv.accept()
        threading.Thread(target=one, args=(c,), daemon=True).start()


def control(port, method, path, body=None):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "X-Engine-Secret": SECRET},
        method=method,
    )
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())


FAILURES = []


def check(label, cond, detail=""):
    print(("ok: " if cond else "FAIL: ") + label + ("" if cond else f"  {detail}"))
    if not cond:
        FAILURES.append(label)


def send_request(proxy_port, fake_access, body, extra_headers=""):
    """One CONNECT + one POST /v1/messages, read the response to EOF (Connection: close)."""
    s = socket.create_connection(("127.0.0.1", proxy_port), timeout=30)
    cred = base64.b64encode(b"m1:pw").decode()
    s.sendall(
        f"CONNECT api.anthropic.com:443 HTTP/1.1\r\nHost: api.anthropic.com:443\r\n"
        f"Proxy-Authorization: Basic {cred}\r\n\r\n".encode()
    )
    read_until_blank(s)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    tls = ctx.wrap_socket(s, server_hostname="api.anthropic.com")
    tls.settimeout(30)
    tls.sendall(
        b"POST /v1/messages HTTP/1.1\r\nHost: api.anthropic.com\r\n"
        + f"Authorization: Bearer {fake_access}\r\n".encode()
        + extra_headers.encode()
        + b"Content-Type: application/json\r\n"
        + (f"Content-Length: {len(body)}\r\n".encode() if "chunked" not in extra_headers else b"")
        + b"Connection: close\r\n\r\n"
        + body
    )
    resp = b""
    while True:
        c = tls.recv(65536)
        if not c:
            break
        resp += c
    tls.close()
    return resp


def chunked_body(payload):
    return f"{len(payload):x}\r\n".encode() + payload + b"\r\n0\r\n\r\n"


def main():
    ca_key, ca_crt = f"{WORK}/ca.key", f"{WORK}/ca.crt"
    up_key, up_crt = f"{WORK}/up.key", f"{WORK}/up.crt"
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {ca_key} -out {ca_crt} -days 2 -nodes -subj /CN=test-ca")
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {up_key} -out {up_crt} -days 2 -nodes -subj /CN=platform.claude.com")

    proxy_port, control_port, egress_port, backend_port = (free_port() for _ in range(4))
    threading.Thread(target=mock_egress, args=(egress_port, up_crt, up_key), daemon=True).start()

    env = dict(
        os.environ,
        CCPROXY_PROXY_PORT=str(proxy_port),
        CCPROXY_CONTROL_PORT=str(control_port),
        CCPROXY_ENGINE_SECRET=SECRET,
        CCPROXY_BACKEND_URL=f"http://127.0.0.1:{backend_port}",
        CCPROXY_CA_CERT=ca_crt,
        CCPROXY_CA_KEY=ca_key,
        CCPROXY_CERTS_DIR=f"{WORK}/certs",
        CCPROXY_BLOCKED_MODEL_FAMILIES="opus,fable",
    )
    engine = subprocess.Popen([sys.executable, f"{HERE}/ccproxy_engine.py"], env=env)
    try:
        for _ in range(50):
            try:
                control(control_port, "GET", "/health")
                break
            except Exception:
                time.sleep(0.2)
        else:
            print("FAIL: engine never healthy")
            sys.exit(1)

        control(control_port, "PUT", "/sessions/m1",
                {"proxyPassword": "pw", "accountProxy": f"http://127.0.0.1:{egress_port}"})
        r = control(control_port, "PUT", "/sessions/m1/credential",
                    {"accessToken": "real-access-xyz", "refreshToken": "rr",
                     "expiresAt": int(time.time()) + 100000})
        fake_access = r.get("fakeAccess")

        cfg = control(control_port, "GET", "/config")
        check("config reflects the startup env var", cfg.get("blockedModelFamilies") == ["opus", "fable"], str(cfg))

        # ── 1. blocked model: rejected, upstream never touched ──
        with UPSTREAM_LOCK:
            before = UPSTREAM["connections"]
        body = b'{"model":"claude-opus-5","messages":[{"role":"user","content":"hi"}]}'
        resp = send_request(proxy_port, fake_access, body)
        check("blocked model: HTTP 400", resp.startswith(b"HTTP/1.1 400"), resp[:60])
        check("blocked model: invalid_request_error type", b"invalid_request_error" in resp, resp[:300])
        check("blocked model: message mentions sonnet", b"sonnet" in resp.lower(), resp[:300])
        with UPSTREAM_LOCK:
            after = UPSTREAM["connections"]
        check("blocked model: upstream never contacted (0 quota spent)", after == before, f"{before} -> {after}")

        # ── 2. allowed model, BIG body (bigger than the sniff cap): forwarded byte-exact ──
        padding = "y" * 20000  # forces the peek-prefix + relay-the-rest path (remaining > 0)
        body = json.dumps({"model": "claude-sonnet-5",
                            "messages": [{"role": "user", "content": padding}]}).encode()
        check("big body exceeds the default sniff cap (exercises the split path)", len(body) > 4096, len(body))
        resp = send_request(proxy_port, fake_access, body)
        check("allowed model: HTTP 200", resp.startswith(b"HTTP/1.1 200"), resp[:60])
        for _ in range(25):
            with UPSTREAM_LOCK:
                if UPSTREAM["last_body_sha"] == hashlib.sha256(body).hexdigest():
                    break
            time.sleep(0.1)
        with UPSTREAM_LOCK:
            got_sha = UPSTREAM["last_body_sha"]
        check("allowed model: upstream received the body byte-exact",
              got_sha == hashlib.sha256(body).hexdigest(), got_sha)

        # ── 3. model pushed past the sniff cap by padding BEFORE it: fails open (forwarded) ──
        with UPSTREAM_LOCK:
            before = UPSTREAM["connections"]
        body = json.dumps({"padding": "z" * 5000, "model": "claude-opus-5",
                            "messages": []}).encode()
        resp = send_request(proxy_port, fake_access, body)
        check("model past the sniff cap: fails open (HTTP 200, not blocked)",
              resp.startswith(b"HTTP/1.1 200"), resp[:60])
        with UPSTREAM_LOCK:
            after = UPSTREAM["connections"]
        check("model past the sniff cap: upstream WAS contacted (fail-open)", after == before + 1, f"{before} -> {after}")

        # ── 4. chunked body: bypasses the gate (documented), still forwarded correctly ──
        payload = b'{"model":"claude-opus-5","messages":[]}'
        resp = send_request(proxy_port, fake_access, chunked_body(payload),
                             extra_headers="Transfer-Encoding: chunked\r\n")
        check("chunked body bypasses the gate (not blocked)", resp.startswith(b"HTTP/1.1 200"), resp[:60])

        # ── 5. live toggle via /config: disable, blocked model now passes; re-enable, blocks again ──
        control(control_port, "PUT", "/config", {"blockedModelFamilies": ""})
        cfg = control(control_port, "GET", "/config")
        check("config: cleared live", cfg.get("blockedModelFamilies") == [], str(cfg))
        body = b'{"model":"claude-opus-5","messages":[]}'
        resp = send_request(proxy_port, fake_access, body)
        check("gate disabled live: opus now forwarded", resp.startswith(b"HTTP/1.1 200"), resp[:60])

        control(control_port, "PUT", "/config", {"blockedModelFamilies": ["opus", "fable"]})
        resp = send_request(proxy_port, fake_access, body)
        check("gate re-enabled live (list form): opus blocked again", resp.startswith(b"HTTP/1.1 400"), resp[:60])

        if FAILURES:
            print(f"\n{len(FAILURES)} FAILURE(S): {FAILURES}")
            sys.exit(1)
        print("\nPASS: shared-quota model gate blocks for free, forwards intact, fails open, and toggles live")
    finally:
        engine.terminate()
        engine.wait(timeout=5)


if __name__ == "__main__":
    main()
