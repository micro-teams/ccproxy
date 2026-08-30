#!/usr/bin/env python3
"""Live test of the engine's usage metering on the streaming path. Boots the real engine, a mock
Anthropic egress that returns an SSE turn (usage split across message_start and message_delta), and a
mock backend that captures what the engine reports. Asserts:

  - the engine forces Accept-Encoding: identity upstream (so the SSE is parseable, not gzip/br/zstd),
  - it POSTs /internal/usage with the exact token counts scraped from both ends of the stream,
  - the client still receives the full SSE.

This guards the regression where compressed responses silently dropped ALL usage metering.
Stdlib + openssl CLI only. Run: python3 proxy-engine/test_metering.py
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = tempfile.mkdtemp(prefix="ccproxy-meter-test-")
SECRET = "test-secret"

SEEN = {"upstream_accept_encoding": None}
BACKEND = {"usage": [], "ratelimit": []}


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


# The SSE turn: input/cache on message_start, final output on message_delta.
SSE_EVENTS = [
    {"type": "message_start", "message": {"model": "claude-opus-5",
     "usage": {"input_tokens": 120, "cache_read_input_tokens": 9000,
               "cache_creation_input_tokens": 40, "output_tokens": 1}}},
    {"type": "content_block_delta", "delta": {"text": "hello "}},
    {"type": "content_block_delta", "delta": {"text": "world"}},
    {"type": "message_delta", "usage": {"output_tokens": 456}},
    {"type": "message_stop"},
]


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
            data = read_until_blank(tls)
            for line in data.split(b"\r\n\r\n", 1)[0].decode(errors="replace").split("\r\n")[1:]:
                k, _, v = line.partition(":")
                if k.strip().lower() == "accept-encoding":
                    SEEN["upstream_accept_encoding"] = v.strip()
            n = 0
            for line in data.decode(errors="replace").split("\r\n"):
                if line.lower().startswith("content-length:"):
                    n = int(line.split(":")[1])
            got = len(data.split(b"\r\n\r\n", 1)[1]) if b"\r\n\r\n" in data else 0
            while got < n:
                c = tls.recv(65536)
                if not c:
                    break
                got += len(c)
            tls.sendall(
                b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                b"Transfer-Encoding: chunked\r\n\r\n"
            )
            for ev in SSE_EVENTS:
                payload = ("data: " + json.dumps(ev) + "\n\n").encode()
                tls.sendall(f"{len(payload):x}\r\n".encode() + payload + b"\r\n")
            tls.sendall(b"0\r\n\r\n")
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


class BackendHandler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n) if n else b""
        try:
            obj = json.loads(body)
        except Exception:
            obj = {}
        if self.path.endswith("/internal/usage"):
            BACKEND["usage"].append(obj)
        elif self.path.endswith("/internal/ratelimit"):
            BACKEND["ratelimit"].append(obj)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"")


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


def main():
    ca_key, ca_crt = f"{WORK}/ca.key", f"{WORK}/ca.crt"
    up_key, up_crt = f"{WORK}/up.key", f"{WORK}/up.crt"
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {ca_key} -out {ca_crt} -days 2 -nodes -subj /CN=test-ca")
    sh(f"openssl req -x509 -newkey rsa:2048 -keyout {up_key} -out {up_crt} -days 2 -nodes -subj /CN=platform.claude.com")

    proxy_port, control_port, egress_port, backend_port = (free_port() for _ in range(4))
    threading.Thread(target=mock_egress, args=(egress_port, up_crt, up_key), daemon=True).start()
    backend = ThreadingHTTPServer(("127.0.0.1", backend_port), BackendHandler)
    threading.Thread(target=backend.serve_forever, daemon=True).start()

    env = dict(
        os.environ,
        CCPROXY_PROXY_PORT=str(proxy_port),
        CCPROXY_CONTROL_PORT=str(control_port),
        CCPROXY_ENGINE_SECRET=SECRET,
        CCPROXY_BACKEND_URL=f"http://127.0.0.1:{backend_port}",
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
        r = control(control_port, "PUT", "/sessions/m1/credential",
                    {"accessToken": "real-access-xyz", "refreshToken": "rr",
                     "expiresAt": int(time.time()) + 100000})
        fake_access = r.get("fakeAccess")

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
        body = b'{"model":"claude-opus-5","messages":[]}'
        # Client asks for gzip — the engine must override it to identity upstream.
        tls.sendall(
            b"POST /v1/messages HTTP/1.1\r\nHost: api.anthropic.com\r\n"
            + f"Authorization: Bearer {fake_access}\r\n".encode()
            + b"Accept-Encoding: gzip, br, zstd\r\nContent-Type: application/json\r\n"
            + f"Content-Length: {len(body)}\r\n".encode()
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

        check("client received the SSE turn", b"message_stop" in resp, resp[:80])
        check("engine forced Accept-Encoding: identity upstream",
              SEEN["upstream_accept_encoding"] == "identity", str(SEEN["upstream_accept_encoding"]))

        # Give the daemon reporter threads a moment.
        for _ in range(25):
            if BACKEND["usage"]:
                break
            time.sleep(0.1)
        check("engine POSTed exactly one usage row", len(BACKEND["usage"]) == 1,
              str(BACKEND["usage"]))
        u = BACKEND["usage"][0] if BACKEND["usage"] else {}
        check("usage: model", u.get("model") == "claude-opus-5", str(u))
        check("usage: input=120 (from message_start)", u.get("inputTokens") == 120, str(u))
        check("usage: output=456 (from message_delta at the END)", u.get("outputTokens") == 456, str(u))
        check("usage: cache_read=9000", u.get("cacheReadTokens") == 9000, str(u))
        check("usage: cache_write=40", u.get("cacheWriteTokens") == 40, str(u))

        if FAILURES:
            print(f"\n{len(FAILURES)} FAILURE(S): {FAILURES}")
            sys.exit(1)
        print("\nPASS: streaming usage is metered from both ends, identity forced upstream")
    finally:
        engine.terminate()
        engine.wait(timeout=5)


if __name__ == "__main__":
    main()
