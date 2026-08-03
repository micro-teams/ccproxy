#!/usr/bin/env python3
#
# One-shot backfill for the Phase 1 credential-store rollout. Reads every existing
# app_data/sessions/<proxyUser>.json and POSTs it to the backend's /internal/credential/session so
# the DB reaches immediate parity with the files (the engine's dual-write only covers sessions that
# capture/refresh AFTER deploy, which could otherwise take hours). Idempotent: the endpoint upserts
# by (scope=SESSION, proxyUser). Run once inside the engine container after Phase 1 is deployed:
#     CCPROXY_SESSION_DIR=/sessions CCPROXY_BACKEND_URL=http://backend:8080 \
#     CCPROXY_ENGINE_SECRET=... python3 backfill_credentials.py
#
import json
import os
import sys
import urllib.request

SESSION_DIR = os.environ.get("CCPROXY_SESSION_DIR", "")
BACKEND_URL = os.environ.get("CCPROXY_BACKEND_URL", "http://backend:8080").rstrip("/")
SECRET = os.environ.get("CCPROXY_ENGINE_SECRET", "")

if not SESSION_DIR or not os.path.isdir(SESSION_DIR):
    sys.exit(f"CCPROXY_SESSION_DIR not set or not a dir: {SESSION_DIR!r}")
if not SECRET:
    sys.exit("CCPROXY_ENGINE_SECRET is required")

ok = fail = 0
for fn in sorted(os.listdir(SESSION_DIR)):
    if not fn.endswith(".json"):
        continue
    user = fn[:-5]
    try:
        with open(os.path.join(SESSION_DIR, fn)) as f:
            d = json.load(f)
        payload = {
            "proxyUser": user,
            "proxyPassword": d.get("proxy_password"),
            "accountProxy": d.get("account_proxy"),
            "realAccess": d.get("real_access"),
            "realRefresh": d.get("real_refresh"),
            "fakeAccess": d.get("fake_access"),
            "fakeRefresh": d.get("fake_refresh"),
            "expiresAt": d.get("expires_at"),
        }
        req = urllib.request.Request(
            f"{BACKEND_URL}/internal/credential/session",
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "X-Engine-Secret": SECRET},
            method="POST",
        )
        urllib.request.urlopen(req, timeout=10).read()
        ok += 1
        print(f"ok   {user}")
    except Exception as e:
        fail += 1
        print(f"FAIL {user}: {str(e)[:120]}")

print(f"\nbackfill done: {ok} ok, {fail} failed")
sys.exit(1 if fail else 0)
