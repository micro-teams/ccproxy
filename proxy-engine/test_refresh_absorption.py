#!/usr/bin/env python3
"""Live test of the engine's refresh absorption (#67): boots the real engine as a subprocess with a
throwaway CA, plays Anthropic behind a mock egress proxy, and drives it through a real client's
moves — login capture, fresh-chain refreshes, near-expiry single-flight (5 concurrent holders, one
upstream rotation), the honest invalid_grant passthrough, and the backoff window.

Stdlib + the openssl CLI only (the engine itself already shells out to openssl). No docker, no
account, no network. Run: python3 proxy-engine/test_refresh_absorption.py
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
WORK = tempfile.mkdtemp(prefix="ccproxy-refresh-test-")


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


def read_http_response(tls):
    """Read one HTTP response (status, headers, Content-Length body) from a socket."""
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = tls.recv(4096)
        if not chunk:
            break
        data += chunk
    head, _, rest = data.partition(b"\r\n\r\n")
    lines = head.decode(errors="replace").split("\r\n")
    code = int(lines[0].split(" ")[1])
    headers = {}
    for line in lines[1:]:
        k, _, v = line.partition(":")
        headers[k.strip().lower()] = v.strip()
    n = int(headers.get("content-length", 0))
    body = rest
    while len(body) < n:
        chunk = tls.recv(4096)
        if not chunk:
            break
        body += chunk
    return code, body


# ── Mock Anthropic behind a mock egress proxy ─────────────────────────────────
# The engine reaches upstream through the session's account proxy (CONNECT, then TLS with
# verification off), so a local egress that answers the CONNECT itself and then plays the token
# endpoint IS Anthropic as far as the engine can tell.
ANTHROPIC = {
    "lock": threading.Lock(),
    "gen": 0,
    "refresh_hits": 0,
    "api_auth_seen": [],
    "real_refresh": None,
    "connects": 0,
}


def issue_pair():
    ANTHROPIC["gen"] += 1
    g = ANTHROPIC["gen"]
    ANTHROPIC["real_refresh"] = f"real-refresh-g{g}"
    return {
        "token_type": "Bearer",
        "access_token": f"real-access-g{g}",
        "refresh_token": ANTHROPIC["real_refresh"],
        "expires_in": 28800,
        "scope": "user:inference user:profile",
    }


def mock_egress(port, certfile, keyfile):
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(32)

    def one(conn):
        try:
            read_until_blank(conn)  # the CONNECT request
            conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile, keyfile)
            tls = ctx.wrap_socket(conn, server_side=True)
            tls.settimeout(10)
            with ANTHROPIC["lock"]:
                ANTHROPIC["connects"] += 1
            while True:
                data = b""
                while b"\r\n\r\n" not in data:
                    chunk = tls.recv(4096)
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
                body = rest
                while len(body) < n:
                    body += tls.recv(4096)
                try:
                    req = json.loads(body.decode(errors="replace"))
                except Exception:
                    req = {}
                with ANTHROPIC["lock"]:
                    if req.get("grant_type") == "authorization_code":
                        resp, code = issue_pair(), 200
                    elif req.get("grant_type") == "refresh_token":
                        ANTHROPIC["refresh_hits"] += 1
                        if req.get("refresh_token") == ANTHROPIC["real_refresh"]:
                            resp, code = issue_pair(), 200
                        else:
                            resp, code = {"error": "invalid_grant"}, 400
                    else:
                        ANTHROPIC["api_auth_seen"].append(headers.get("authorization", ""))
                        resp, code = {"ok": True}, 200
                payload = json.dumps(resp).encode()
                reason = "OK" if code == 200 else "Bad Request"
                tls.sendall(
                    f"HTTP/1.1 {code} {reason}\r\nContent-Type: application/json\r\n"
                    f"Content-Length: {len(payload)}\r\n\r\n".encode() + payload
                )
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


# ── Client-side: one request through the engine's MITM, as claude would ────────
def via_proxy(proxy_port, user, pw, method, path, body=None, bearer=None):
    s = socket.create_connection(("127.0.0.1", proxy_port), timeout=15)
    cred = base64.b64encode(f"{user}:{pw}".encode()).decode()
    s.sendall(
        f"CONNECT platform.claude.com:443 HTTP/1.1\r\nHost: platform.claude.com:443\r\n"
        f"Proxy-Authorization: Basic {cred}\r\n\r\n".encode()
    )
    read_until_blank(s)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    tls = ctx.wrap_socket(s, server_hostname="platform.claude.com")
    tls.settimeout(15)
    payload = json.dumps(body).encode() if body is not None else b""
    req = (
        f"{method} {path} HTTP/1.1\r\nHost: platform.claude.com\r\n"
        f"Content-Type: application/json\r\nContent-Length: {len(payload)}\r\n"
        f"Connection: close\r\n"
    )
    if bearer:
        req += f"Authorization: Bearer {bearer}\r\n"
    tls.sendall(req.encode() + "\r\n".encode() + payload)
    code, raw = read_http_response(tls)
    tls.close()
    try:
        return code, json.loads(raw.decode(errors="replace"))
    except Exception:
        return code, {}


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
    if cond:
        print(f"ok: {label}")
    else:
        print(f"FAIL: {label} {detail}")
        FAILURES.append(label)


def main():
    # Throwaway PKI: one CA for the engine's MITM leafs, one self-signed cert for mock Anthropic.
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
        CCPROXY_REFRESH_BACKOFF="2",
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
            print("FAIL: engine never became healthy")
            sys.exit(1)

        control(
            control_port,
            "PUT",
            "/sessions/m1",
            {"proxyPassword": "pw", "accountProxy": f"http://127.0.0.1:{egress_port}"},
        )

        print("== login: capture mints the fake pair ==")
        code, tok = via_proxy(
            proxy_port, "m1", "pw", "POST", "/v1/oauth/token",
            {"grant_type": "authorization_code", "code": "c", "state": "s", "client_id": "cid"},
        )
        fa, fr = tok.get("access_token", ""), tok.get("refresh_token", "")
        check("login returns 200", code == 200, f"got {code}")
        check("client got a FAKE access token", fa.startswith("sk-ant-oat01-"), fa[:20])
        check("client got a FAKE refresh token", fr.startswith("sk-ant-ort01-"), fr[:20])

        print("== API call: fake access swaps to the real one ==")
        code, _ = via_proxy(proxy_port, "m1", "pw", "POST", "/v1/messages", {"model": "x"}, bearer=fa)
        check("API call passes", code == 200, f"got {code}")
        check("upstream saw the REAL g1 access", ANTHROPIC["api_auth_seen"][-1:] == ["Bearer real-access-g1"],
              str(ANTHROPIC["api_auth_seen"][-1:]))

        print("== fresh chain: refreshes are absorbed, pair is stable, upstream untouched ==")
        for i in (1, 2):
            code, tok = via_proxy(
                proxy_port, "m1", "pw", "POST", "/v1/oauth/token",
                {"grant_type": "refresh_token", "refresh_token": fr, "client_id": "cid"},
            )
            check(f"absorbed refresh #{i} returns 200", code == 200, f"got {code}")
            check(f"refresh #{i} returns the SAME pair",
                  tok.get("access_token") == fa and tok.get("refresh_token") == fr, str(tok)[:120])
            check(f"refresh #{i} expiry is the real chain's", tok.get("expires_in", 0) > 20000,
                  str(tok.get("expires_in")))
        check("no real refresh happened", ANTHROPIC["refresh_hits"] == 0, str(ANTHROPIC["refresh_hits"]))

        print("== near expiry: 5 concurrent holders, ONE upstream rotation, nobody re-keyed ==")
        # The setup-token injection door doubles as the test's time machine: overwrite the real
        # chain with one that is nearly expired. It must NOT re-key the fakes (mint-if-absent).
        r = control(control_port, "PUT", "/sessions/m1/credential",
                    {"accessToken": "real-access-g1", "refreshToken": ANTHROPIC["real_refresh"],
                     "expiresAt": int(time.time()) + 60})
        check("credential injection keeps the stable fake", r.get("fakeAccess") == fa, str(r)[:120])
        results = []

        def one_refresh():
            results.append(via_proxy(
                proxy_port, "m1", "pw", "POST", "/v1/oauth/token",
                {"grant_type": "refresh_token", "refresh_token": fr, "client_id": "cid"},
            ))

        threads = [threading.Thread(target=one_refresh) for _ in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        check("all 5 holders got 200", all(c == 200 for c, _ in results),
              str([c for c, _ in results]))
        check("all 5 kept the SAME stable pair",
              all(t.get("access_token") == fa and t.get("refresh_token") == fr for _, t in results),
              str(results)[:160])
        check("exactly ONE upstream rotation", ANTHROPIC["refresh_hits"] == 1,
              str(ANTHROPIC["refresh_hits"]))
        check("all 5 see the fresh expiry", all(t.get("expires_in", 0) > 20000 for _, t in results),
              str([t.get("expires_in") for _, t in results]))

        print("== the captured g2 chain carries the API traffic ==")
        code, _ = via_proxy(proxy_port, "m1", "pw", "POST", "/v1/messages", {"model": "x"}, bearer=fa)
        check("API call passes on the rotated chain", code == 200, f"got {code}")
        check("upstream saw the REAL g2 access", ANTHROPIC["api_auth_seen"][-1:] == ["Bearer real-access-g2"],
              str(ANTHROPIC["api_auth_seen"][-1:]))

        print("== dead chain: honest invalid_grant passthrough, then backoff shields upstream ==")
        control(control_port, "PUT", "/sessions/m1/credential",
                {"accessToken": "real-access-dead", "refreshToken": "real-refresh-dead",
                 "expiresAt": int(time.time()) + 60})
        hits_before = ANTHROPIC["refresh_hits"]
        code, tok = via_proxy(
            proxy_port, "m1", "pw", "POST", "/v1/oauth/token",
            {"grant_type": "refresh_token", "refresh_token": fr, "client_id": "cid"},
        )
        check("dead chain passes the 400 through", code == 400, f"got {code}")
        code, tok = via_proxy(
            proxy_port, "m1", "pw", "POST", "/v1/oauth/token",
            {"grant_type": "refresh_token", "refresh_token": fr, "client_id": "cid"},
        )
        check("inside backoff: answered locally, still the stable pair",
              code == 200 and tok.get("access_token") == fa, f"got {code} {str(tok)[:80]}")
        check("backoff kept upstream at one attempt", ANTHROPIC["refresh_hits"] == hits_before + 1,
              str(ANTHROPIC["refresh_hits"]))

        print("== a refresh token we never issued is NOT absorbed ==")
        code, _ = via_proxy(
            proxy_port, "m1", "pw", "POST", "/v1/oauth/token",
            {"grant_type": "refresh_token", "refresh_token": "sk-ant-ort01-" + "f" * 64,
             "client_id": "cid"},
        )
        check("foreign refresh token is refused upstream", code == 400, f"got {code}")

        if FAILURES:
            print(f"\n{len(FAILURES)} FAILURE(S): {FAILURES}")
            sys.exit(1)
        print("\nPASS: refresh absorption holds — stable pair, single-flight, honest failure")
    finally:
        engine.terminate()
        engine.wait(timeout=5)


if __name__ == "__main__":
    main()
