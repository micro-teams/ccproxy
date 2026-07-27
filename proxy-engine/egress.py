#!/usr/bin/env python3
"""
egress-proxy — the bundle's default upstream proxy.

A minimal HTTP proxy (CONNECT tunnelling) that simply forwards through the host's own network. It is
the "default proxy to fill in" for accounts before any per-account network separation exists: an
account's `proxy` defaults to http://egress-proxy:7890, and the MITM engine makes its upstream
Anthropic connections through it. Swap the account's proxy for a real per-account egress later.
"""
import os
import select
import socket
import threading

PORT = int(os.environ.get("EGRESS_PORT", "7890"))


def recv_headers(s):
    d = b""
    while b"\r\n\r\n" not in d:
        c = s.recv(1)
        if not c:
            return None
        d += c
    return d


def pipe(a, b):
    a.setblocking(False)
    b.setblocking(False)
    pipes = {a: b, b: a}
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 120)
            if not r:
                break
            for s in r:
                data = s.recv(8192)
                if not data:
                    return
                pipes[s].sendall(data)
    finally:
        for s in (a, b):
            try:
                s.close()
            except Exception:
                pass


def handle(cs):
    try:
        head = recv_headers(cs)
        if not head:
            return
        request_line = head.split(b"\r\n", 1)[0].decode(errors="replace")
        parts = request_line.split(" ")
        if len(parts) < 2 or parts[0] != "CONNECT":
            cs.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
            return
        host, _, port = parts[1].partition(":")
        up = socket.create_connection((host, int(port or 443)), timeout=30)
        cs.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        pipe(cs, up)
    except Exception:
        try:
            cs.close()
        except Exception:
            pass


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", PORT))
    sock.listen(128)
    print(f"egress-proxy listening on :{PORT}", flush=True)
    while True:
        c, _ = sock.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()
