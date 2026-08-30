#!/usr/bin/env python3
"""Live test of the engine's upstream connect-retry. The TLS handshake to Anthropic (through the
egress hop) fails intermittently with a bare EOF; the engine must retry on a fresh connection rather
than drop the client with no response (which a fronting proxy and Claude Code both read as
ConnectionRefused). Two cases:

  1. RECOVERY  — the mock egress kills the first N connections at handshake time, then serves. The
                 client must still get a 200: the retry reconnected.
  2. EXHAUSTED — the egress kills every connection. The client must get a retryable 502 (Bad
                 Gateway), NOT a bare connection drop.

Stdlib + openssl CLI only. Run: python3 proxy-engine/test_upstream_retry.py
"""

import base64
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
WORK = tempfile.mkdtemp(prefix="ccproxy-retry-test-")


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


def control(port, method, path, body=None):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())


FAILURES = []


def check(label, cond, detail=""):
    print(("ok: " if cond else "FAIL: ") + label + ("" if cond else f"  {detail}"))
    if not cond:
        FAILURES.append(label)


def make_egress(certfile, keyfile, fail_first):
    """A mock Anthropic egress. The first `fail_first` accepted connections answer the CONNECT 200
    then close immediately (so the engine's TLS handshake gets EOF); the rest serve a tiny 200. A
    shared, locked counter makes it deterministic across the retry's fresh connections."""
    state = {"n": 0}
    lock = threading.Lock()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    port = free_port()
    srv.bind(("127.0.0.1", port))
    srv.listen(32)

    def one(conn):
        try:
            read_until_blank(conn)  # the CONNECT line
            conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            with lock:
                state["n"] += 1
                idx = state["n"]
            if fail_first < 0 or idx <= fail_first:
                # Kill the tunnel before/at TLS: the engine's wrap_socket sees a bare EOF.
                conn.close()
                return
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile, keyfile)
            tls = ctx.wrap_socket(conn, server_side=True)
            tls.settimeout(30)
            data = read_until_blank(tls)
            headers = {}
            for line in data.split(b"\r\n\r\n", 1)[0].decode(errors="replace").split("\r\n")[1:]:
                k, _, v = line.partition(":")
                headers[k.strip().lower()] = v.strip()
            n = int(headers.get("content-length", 0))
            got = len(data.split(b"\r\n\r\n", 1)[1]) if b"\r\n\r\n" in data else 0
            while got < n:
                chunk = tls.recv(65536)
                if not chunk:
                    break
                got += len(chunk)
            body = b'{"ok":true}'
            tls.sendall(
                b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + f"Content-Length: {len(body)}\r\n".encode()
                + b"Connection: close\r\n\r\n"
                + body
            )
            tls.close()
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def loop():
        while True:
            c, _ = srv.accept()
            threading.Thread(target=one, args=(c,), daemon=True).start()

    threading.Thread(target=loop, daemon=True).start()
    return port, state


def boot_engine(egress_port, ca_crt, ca_key, attempts):
    proxy_port, control_port = free_port(), free_port()
    env = dict(
        os.environ,
        CCPROXY_PROXY_PORT=str(proxy_port),
        CCPROXY_CONTROL_PORT=str(control_port),
        CCPROXY_ENGINE_SECRET="",
        CCPROXY_BACKEND_URL="",
        CCPROXY_CA_CERT=ca_crt,
        CCPROXY_CA_KEY=ca_key,
        CCPROXY_CERTS_DIR=f"{WORK}/certs-{proxy_port}",
        CCPROXY_UPSTREAM_CONNECT_ATTEMPTS=str(attempts),
        CCPROXY_UPSTREAM_RETRY_BACKOFF="0.05",
    )
    engine = subprocess.Popen([sys.executable, f"{HERE}/ccproxy_engine.py"], env=env)
    for _ in range(50):
        try:
            control(control_port, "GET", "/health")
            break
        except Exception:
            time.sleep(0.2)
    else:
        print("FAIL: engine never healthy")
        engine.terminate()
        sys.exit(1)
    control(control_port, "PUT", "/sessions/m1",
            {"proxyPassword": "pw", "accountProxy": f"http://127.0.0.1:{egress_port}"})
    r = control(control_port, "PUT", "/sessions/m1/credential",
                {"accessToken": "real-access-xyz", "refreshToken": "rr",
                 "expiresAt": int(time.time()) + 100000})
    return engine, proxy_port, r.get("fakeAccess")


def one_request(proxy_port, fake_access):
    """Send one POST /v1/messages through the MITM; return the response status line."""
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
    body = b'{"model":"x","messages":[]}'
    tls.sendall(
        b"POST /v1/messages HTTP/1.1\r\nHost: api.anthropic.com\r\n"
        + f"Authorization: Bearer {fake_access}\r\n".encode()
        + b"Content-Type: application/json\r\n"
        + f"Content-Length: {len(body)}\r\n".encode()
        + b"Connection: close\r\n\r\n"
        + body
    )
    buf = read_until_blank(tls)
    tls.close()
    return buf.split(b"\r\n", 1)[0].decode(errors="replace")


def main():
    ca_key, ca_crt = f"{WORK}/ca.key", f"{WORK}/ca.crt"
    up_key, up_crt = f"{WORK}/up.key", f"{WORK}/up.crt"
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {ca_key} -out {ca_crt} -days 2 -nodes -subj /CN=test-ca")
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {up_key} -out {up_crt} -days 2 -nodes -subj /CN=platform.claude.com")

    # CASE 1 — recover: egress kills the first 2 handshakes, engine (3 attempts) reconnects and wins.
    eport, state = make_egress(up_crt, up_key, fail_first=2)
    engine, pport, fake = boot_engine(eport, ca_crt, ca_key, attempts=3)
    try:
        status = one_request(pport, fake)
        check("recovers after transient upstream EOF (client sees 200)", status.endswith("200 OK"), status)
        check("engine actually retried (>=3 upstream connects made)", state["n"] >= 3, f"n={state['n']}")
    finally:
        engine.terminate()
        engine.wait(timeout=5)

    # CASE 2 — exhausted: egress kills every handshake; client must get a retryable 502, not a drop.
    eport2, _ = make_egress(up_crt, up_key, fail_first=-1)
    engine2, pport2, fake2 = boot_engine(eport2, ca_crt, ca_key, attempts=2)
    try:
        status = one_request(pport2, fake2)
        check("exhausted retries answer 502 (not a bare connection drop)", "502" in status, status or "<empty>")
    finally:
        engine2.terminate()
        engine2.wait(timeout=5)

    if FAILURES:
        print(f"\n{len(FAILURES)} FAILURE(S): {FAILURES}")
        sys.exit(1)
    print("\nPASS: upstream connect failures are retried, and give a 502 when exhausted")


if __name__ == "__main__":
    main()
