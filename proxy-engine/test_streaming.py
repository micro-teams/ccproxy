#!/usr/bin/env python3
"""Live test of the engine's streaming relay for large bodies: boots the real engine with a throwaway
CA, plays Anthropic behind a mock egress, and pushes a large request body up + a large chunked
response body down through the MITM — asserting byte-integrity both ways, that the real bearer is
swapped in, and that it completes fast (the old fully-buffered O(n^2) path would stall on this).

Stdlib + openssl CLI only. Run: python3 proxy-engine/test_streaming.py
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
WORK = tempfile.mkdtemp(prefix="ccproxy-stream-test-")

BODY_MB = 40
REQ_SIZE = BODY_MB * 1024 * 1024
RESP_SIZE = BODY_MB * 1024 * 1024


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


SEEN = {"req_sha": None, "req_len": 0, "auth": None}


def mock_egress(port, certfile, keyfile):
    """Anthropic: reads the full (large) request body, records its sha256, and replies with a large
    CHUNKED body whose sha256 is announced in a header so the client can verify the down path."""
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(32)

    def one(conn):
        try:
            read_until_blank(conn)  # CONNECT
            conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile, keyfile)
            tls = ctx.wrap_socket(conn, server_side=True)
            tls.settimeout(30)
            while True:
                data = b""
                while b"\r\n\r\n" not in data:
                    chunk = tls.recv(65536)
                    if not chunk:
                        return
                    data += chunk
                head, _, rest = data.partition(b"\r\n\r\n")
                lines = head.decode(errors="replace").split("\r\n")
                headers = {}
                for line in lines[1:]:
                    k, _, v = line.partition(":")
                    headers[k.strip().lower()] = v.strip()
                n = int(headers.get("content-length", 0))
                h = hashlib.sha256()
                h.update(rest)
                got = len(rest)
                while got < n:
                    chunk = tls.recv(min(65536, n - got))
                    if not chunk:
                        break
                    h.update(chunk)
                    got += len(chunk)
                SEEN["req_sha"] = h.hexdigest()
                SEEN["req_len"] = got
                SEEN["auth"] = headers.get("authorization", "")

                # Reply with a big chunked body; announce its sha in a header.
                block = (b"z" * 65536)
                nblocks = RESP_SIZE // len(block)
                rh = hashlib.sha256()
                tls.sendall(
                    b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                    b"Transfer-Encoding: chunked\r\n"
                )
                # sha of the full response body computed up front (deterministic content)
                rh.update(block * nblocks)
                tls.sendall(f"x-body-sha: {rh.hexdigest()}\r\n\r\n".encode())
                for _ in range(nblocks):
                    tls.sendall(f"{len(block):x}\r\n".encode() + block + b"\r\n")
                tls.sendall(b"0\r\n\r\n")
                return
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


def main():
    ca_key, ca_crt = f"{WORK}/ca.key", f"{WORK}/ca.crt"
    up_key, up_crt = f"{WORK}/up.key", f"{WORK}/up.crt"
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {ca_key} -out {ca_crt} -days 2 -nodes -subj /CN=test-ca")
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {up_key} -out {up_crt} -days 2 -nodes -subj /CN=platform.claude.com")

    proxy_port, control_port, egress_port = free_port(), free_port(), free_port()
    threading.Thread(target=mock_egress, args=(egress_port, up_crt, up_key), daemon=True).start()

    env = dict(
        os.environ,
        CCPROXY_PROXY_PORT=str(proxy_port),
        CCPROXY_CONTROL_PORT=str(control_port),
        CCPROXY_ENGINE_SECRET="",
        CCPROXY_BACKEND_URL="",
        CCPROXY_CA_CERT=ca_crt,
        CCPROXY_CA_KEY=ca_key,
        CCPROXY_CERTS_DIR=f"{WORK}/certs",
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
        # Inject a real access token so the engine has a fake->real swap to perform.
        r = control(control_port, "PUT", "/sessions/m1/credential",
                    {"accessToken": "real-access-xyz", "refreshToken": "rr",
                     "expiresAt": int(time.time()) + 100000})
        fake_access = r.get("fakeAccess")
        check("engine minted a fake access token", bool(fake_access), str(r)[:120])

        # Build a big deterministic request body and its sha.
        blk = b"q" * 65536
        nreq = REQ_SIZE // len(blk)
        req_sha = hashlib.sha256(blk * nreq).hexdigest()

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
        tls.settimeout(60)

        t0 = time.time()
        tls.sendall(
            f"POST /v1/messages HTTP/1.1\r\nHost: api.anthropic.com\r\n"
            f"Authorization: Bearer {fake_access}\r\nContent-Type: application/octet-stream\r\n"
            f"Content-Length: {REQ_SIZE}\r\nConnection: close\r\n\r\n".encode()
        )
        for _ in range(nreq):
            tls.sendall(blk)

        # Read response: status + headers, then the chunked body.
        buf = b""
        while b"\r\n\r\n" not in buf:
            c = tls.recv(65536)
            if not c:
                break
            buf += c
        head, _, rest = buf.partition(b"\r\n\r\n")
        hlines = head.decode(errors="replace").split("\r\n")
        status_ok = hlines[0].endswith("200 OK")
        announced = ""
        for line in hlines[1:]:
            if line.lower().startswith("x-body-sha:"):
                announced = line.split(":", 1)[1].strip()
        # Dechunk the response body.
        body = bytearray()
        stream = bytearray(rest)

        def fill():
            c = tls.recv(65536)
            if c:
                stream.extend(c)
            return bool(c)

        done = False
        while not done:
            while b"\r\n" not in stream:
                if not fill():
                    done = True
                    break
            if done:
                break
            line, _, remainder = bytes(stream).partition(b"\r\n")
            stream[:] = remainder
            try:
                size = int(line.split(b";")[0], 16)
            except ValueError:
                break
            if size == 0:
                done = True
                break
            while len(stream) < size + 2:
                if not fill():
                    break
            body.extend(stream[:size])
            stream[:] = stream[size + 2:]
        elapsed = time.time() - t0
        tls.close()

        check("response status 200", status_ok, head[:60].decode(errors="replace"))
        check("upstream saw the REAL bearer (not the fake)",
              SEEN["auth"] == "Bearer real-access-xyz", SEEN["auth"])
        check(f"upstream received the full {BODY_MB}MB request body",
              SEEN["req_len"] == REQ_SIZE, f"{SEEN['req_len']} vs {REQ_SIZE}")
        check("request body arrived intact (sha match)", SEEN["req_sha"] == req_sha,
              f"{SEEN['req_sha']} vs {req_sha}")
        check(f"client received the full {BODY_MB}MB response body",
              len(body) == RESP_SIZE, f"{len(body)} vs {RESP_SIZE}")
        check("response body arrived intact (sha match)",
              hashlib.sha256(bytes(body)).hexdigest() == announced,
              f"{hashlib.sha256(bytes(body)).hexdigest()} vs {announced}")
        # The old buffered path spent ~50s just concatenating a 40MB body each way; streaming is
        # near-linear. Generous ceiling so the assertion is about "not stalling", not raw throughput.
        check(f"streamed {2*BODY_MB}MB round-trip fast (no O(n^2) stall): {elapsed:.1f}s",
              elapsed < 30, f"{elapsed:.1f}s")

        if FAILURES:
            print(f"\n{len(FAILURES)} FAILURE(S): {FAILURES}")
            sys.exit(1)
        print("\nPASS: large bodies stream through intact, fast, with the real bearer swapped in")
    finally:
        engine.terminate()
        engine.wait(timeout=5)


if __name__ == "__main__":
    main()
