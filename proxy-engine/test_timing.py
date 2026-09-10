#!/usr/bin/env python3
"""Live test of the engine's per-request structured timing (CCPROXY_TIMING_LOG). Boots the real
engine with a throwaway CA and drives four scenarios through it, asserting that each phase lands in
the RIGHT bucket and that failure paths still emit a record:

  1. upstream delays the first body block  -> upstream_first_body_wait large, client_first_write small
  2. client stalls reading the body        -> client_first_write large (client-side write blocking)
  3. upstream never sends a status line     -> record still emitted on the timeout/error path
  4. every upstream connect fails           -> record emitted with status 502 and retries == attempts

Stdlib + openssl CLI only. Run: python3 proxy-engine/test_timing.py
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

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = tempfile.mkdtemp(prefix="ccproxy-timing-test-")
ATTEMPTS = 3
FAILURES = []


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
        c = sock.recv(4096)
        if not c:
            return data
        data += c
    return data


def check(label, cond, detail=""):
    if not cond:
        FAILURES.append(label)
    print(("ok: " if cond else "FAIL: ") + label + ("" if cond else f"  {detail}"))


def control(port, method, path, body=None):
    import urllib.request
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read() or b"{}")


def mock_egress(port, certfile, keyfile, mode):
    """Anthropic stand-in with a configurable response behaviour keyed by `mode`."""
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(32)

    def drain_request(tls):
        data = b""
        while b"\r\n\r\n" not in data:
            c = tls.recv(65536)
            if not c:
                return None
            data += c
        head, _, rest = data.partition(b"\r\n\r\n")
        headers = {}
        for line in head.decode(errors="replace").split("\r\n")[1:]:
            k, _, v = line.partition(":")
            headers[k.strip().lower()] = v.strip()
        n = int(headers.get("content-length", 0))
        got = len(rest)
        while got < n:
            c = tls.recv(min(65536, n - got))
            if not c:
                break
            got += len(c)
        return True

    def one(conn):
        try:
            read_until_blank(conn)  # CONNECT
            conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile, keyfile)
            tls = ctx.wrap_socket(conn, server_side=True)
            tls.settimeout(30)
            if drain_request(tls) is None:
                return
            if mode == "timeout":
                time.sleep(10)  # never send a status line; engine's upstream read must time out first
                return
            hdr = (b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                   b"Transfer-Encoding: chunked\r\n\r\n")
            if mode == "delay_body":
                tls.sendall(hdr)
                time.sleep(1.0)  # headers now, first body block only after a clear gap
                block = b"event: ping\n\n"
                tls.sendall(f"{len(block):x}\r\n".encode() + block + b"\r\n0\r\n\r\n")
            elif mode == "slow_client":
                # No upstream delay at all: send headers + a body far larger than any socket buffer,
                # immediately. If the client stalls, the engine's writes back up — and that stall must
                # land on the client-facing side (stream_body), never in upstream_first_body_wait.
                tls.sendall(hdr)
                block = b"d" * 65536
                for _ in range(128):  # ~8MB, well past default socket buffers -> writes block if client stalls
                    tls.sendall(f"{len(block):x}\r\n".encode() + block + b"\r\n")
                tls.sendall(b"0\r\n\r\n")
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


def connect_tls(proxy_port, user, rcvbuf=None):
    s = socket.create_connection(("127.0.0.1", proxy_port), timeout=30)
    if rcvbuf:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)  # set before TLS consumes the fd
    cred = base64.b64encode(f"{user}:pw".encode()).decode()
    s.sendall(f"CONNECT api.anthropic.com:443 HTTP/1.1\r\nHost: api.anthropic.com:443\r\n"
              f"Proxy-Authorization: Basic {cred}\r\n\r\n".encode())
    read_until_blank(s)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    tls = ctx.wrap_socket(s, server_hostname="api.anthropic.com")
    tls.settimeout(30)
    return s, tls


def send_req(tls, fake):
    tls.sendall(f"POST /v1/messages HTTP/1.1\r\nHost: api.anthropic.com\r\n"
                f"Authorization: Bearer {fake}\r\nContent-Type: application/json\r\n"
                f"x-client-request-id: rid-test\r\nContent-Length: 2\r\nConnection: close\r\n\r\n"
                f"{{}}".encode())


def register(control_port, user, egress_port):
    control(control_port, "PUT", f"/sessions/{user}",
            {"proxyPassword": "pw", "accountProxy": f"http://127.0.0.1:{egress_port}"})
    r = control(control_port, "PUT", f"/sessions/{user}/credential",
                {"accessToken": "real-access-xyz", "refreshToken": "rr",
                 "expiresAt": int(time.time()) + 100000})
    return r.get("fakeAccess")


def read_timing(path, machine, tries=30):
    for _ in range(tries):
        if os.path.exists(path):
            recs = [json.loads(x) for x in open(path).read().splitlines() if x.strip()]
            hit = [r for r in recs if r.get("machine") == machine]
            if hit:
                return hit[-1]
        time.sleep(0.2)
    return None


def main():
    ca_key, ca_crt = f"{WORK}/ca.key", f"{WORK}/ca.crt"
    up_key, up_crt = f"{WORK}/up.key", f"{WORK}/up.crt"
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {ca_key} -out {ca_crt} -days 2 -nodes -subj /CN=test-ca")
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {up_key} -out {up_crt} -days 2 -nodes -subj /CN=platform.claude.com")

    proxy_port, control_port = free_port(), free_port()
    p_delay, p_slow, p_timeout = free_port(), free_port(), free_port()
    dead_port = free_port()  # nothing ever listens here -> every upstream connect fails
    for port, mode in ((p_delay, "delay_body"), (p_slow, "slow_client"), (p_timeout, "timeout")):
        threading.Thread(target=mock_egress, args=(port, up_crt, up_key, mode), daemon=True).start()

    timing = f"{WORK}/timing.jsonl"
    env = dict(os.environ, CCPROXY_PROXY_PORT=str(proxy_port), CCPROXY_CONTROL_PORT=str(control_port),
               CCPROXY_ENGINE_SECRET="", CCPROXY_BACKEND_URL="", CCPROXY_CA_CERT=ca_crt,
               CCPROXY_CA_KEY=ca_key, CCPROXY_CERTS_DIR=f"{WORK}/certs", CCPROXY_TIMING_LOG=timing,
               CCPROXY_UPSTREAM_CONNECT_ATTEMPTS=str(ATTEMPTS), CCPROXY_UPSTREAM_RETRY_BACKOFF="0.05",
               CCPROXY_UPSTREAM_TIMEOUT="2")
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

        # 1) upstream delays the first body block ────────────────────────────────
        fake = register(control_port, "mdelay", p_delay)
        _, tls = connect_tls(proxy_port, "mdelay")
        send_req(tls, fake)
        tls.recv(65536)
        while tls.recv(65536):
            pass
        tls.close()
        rec = read_timing(timing, "mdelay")
        check("delay_body: record emitted", rec is not None, "")
        if rec:
            ms = rec["ms"]
            check("delay_body: upstream_first_body_wait ~1s",
                  (ms.get("upstream_first_body_wait") or 0) >= 800, str(ms))
            check("delay_body: client_first_write stayed small (<300ms)",
                  (ms.get("client_first_write") or 0) < 300, str(ms))
            check("delay_body: req_id + status carried", rec.get("req_id") == "rid-test"
                  and rec.get("status") == 200, str(rec)[:140])

        # 2) client stalls reading the body ──────────────────────────────────────
        fake = register(control_port, "mslow", p_slow)
        _, tls = connect_tls(proxy_port, "mslow")
        send_req(tls, fake)
        # read only the head, then stall ~1.2s; the large body backs up in the engine's write to us
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += tls.recv(4096)
        time.sleep(1.2)
        try:
            while tls.recv(1 << 20):
                pass
        except Exception:
            pass
        tls.close()
        rec = read_timing(timing, "mslow")
        check("slow_client: record emitted", rec is not None, "")
        if rec:
            ms = rec["ms"]
            # the client stall must show on the client-facing side, NOT be blamed on upstream
            check("slow_client: stall captured on client side (stream_body >=800ms)",
                  (ms.get("stream_body") or 0) >= 800, str(ms))
            check("slow_client: NOT misattributed to upstream (upstream_first_body_wait <300ms)",
                  (ms.get("upstream_first_body_wait") or 0) < 300, str(ms))

        # 3) upstream never sends a status line -> upstream read times out ────────
        fake = register(control_port, "mtimeout", p_timeout)
        _, tls = connect_tls(proxy_port, "mtimeout")
        send_req(tls, fake)
        try:
            tls.settimeout(6)
            while tls.recv(65536):
                pass
        except Exception:
            pass
        tls.close()
        rec = read_timing(timing, "mtimeout")
        check("timeout: record STILL emitted on the error path", rec is not None, "")
        if rec:
            ms = rec["ms"]
            check("timeout: request reached upstream (upload timed, no status)",
                  ms.get("req_body_upload") is not None
                  and ms.get("upstream_first_body_wait") is None, str(ms))
            check("timeout: total covers the ~2s upstream wait", (ms.get("total") or 0) >= 1500, str(ms))

        # 4) every upstream connect fails -> 502 with retries == attempts ─────────
        fake = register(control_port, "mdead", dead_port)
        _, tls = connect_tls(proxy_port, "mdead")
        send_req(tls, fake)
        try:
            while tls.recv(65536):
                pass
        except Exception:
            pass
        tls.close()
        rec = read_timing(timing, "mdead")
        check("retry_exhaustion: record emitted", rec is not None, "")
        if rec:
            check("retry_exhaustion: status 502", rec.get("status") == 502, str(rec)[:140])
            check(f"retry_exhaustion: retries == {ATTEMPTS} (not 0)",
                  rec.get("retries") == ATTEMPTS, str(rec)[:140])

        if FAILURES:
            print(f"\n{len(FAILURES)} FAILURE(S): {FAILURES}")
            sys.exit(1)
        print("\nPASS: per-request timing attributes phases correctly and records every failure path")
    finally:
        engine.terminate()
        engine.wait(timeout=5)


if __name__ == "__main__":
    main()
