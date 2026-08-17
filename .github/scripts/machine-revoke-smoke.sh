#!/usr/bin/env bash
# Machine revocation smoke test: prove DELETE /machine/{id} is a RELIABLE revocation of exactly one
# machine's ticket — the engine's live session is dropped, the durable credential row is gone, the
# revocation survives an engine restart (no resurrection from the DB reload), a racing credential
# write cannot re-plant it, and — when the engine cannot confirm the drop — the DELETE fails
# honestly with 502 and deletes NOTHING. Needs NO real Anthropic account: the "captured" credential
# is planted through the same internal endpoints the engine itself uses. Run from an up compose
# bundle directory (has .env).
set -euo pipefail

GW="http://localhost:$(grep -E '^NGINX_HTTP_PORT=' .env | cut -d= -f2 || echo 80)/ccproxy"
ENGINE_SECRET="$(grep -E '^ENGINE_SECRET=' .env | cut -d= -f2)"
SUPER="$(grep -E '^SUPERADMIN_PASSWORD=' .env | cut -d= -f2)"

jqget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

# HTTP from INSIDE the compose network (the engine control API and the backend's /internal/*
# endpoints are deliberately not published to the host). The proxy-engine container's python is the
# client — its image has no curl, python IS its runtime. Prints "<status> <body>".
inet() {
  docker compose exec -T -e SECRET="$ENGINE_SECRET" proxy-engine python3 - "$@" <<'PY'
import json, os, sys, urllib.request, urllib.error
method, url = sys.argv[1], sys.argv[2]
data = sys.argv[3].encode() if len(sys.argv) > 3 else None
req = urllib.request.Request(url, data=data, method=method,
    headers={"Content-Type": "application/json", "X-Engine-Secret": os.environ["SECRET"]})
try:
    r = urllib.request.urlopen(req, timeout=10)
    print(r.status, r.read().decode().strip())
except urllib.error.HTTPError as e:
    print(e.code, e.read().decode().strip())
PY
}

expect_status() { # expect_status <want> <got-line> <label>
  local want="$1" got="$2" label="$3"
  case "$got" in
    "$want"|"$want "*) echo "ok: $label -> $want" ;;
    *) echo "FAIL: $label — expected $want, got: $got"; exit 1 ;;
  esac
}

echo "== provision a tenant + two hostless machines =="
TOK="$(curl -s -X POST "$GW/superadmin/login" -H 'Content-Type: application/json' -d "{\"password\":\"$SUPER\"}" | jqget "['token']")"
A="Authorization: Bearer $TOK"
TID="$(curl -s -X POST "$GW/tenant" -H "$A" -H 'Content-Type: application/json' -d '{"name":"revoke-ci"}' | jqget "['id']")"
TSEC="$(curl -s -X POST "$GW/tenant/$TID/secret" -H "$A" -H 'Content-Type: application/json' -d '{}' | jqget "['secret']")"
T="Authorization: Bearer $TSEC"
MID1="$(curl -s -X POST "$GW/machine" -H "$T" -H 'Content-Type: application/json' -d '{"label":"revoke-ci-1"}' | jqget "['id']")"
MID2="$(curl -s -X POST "$GW/machine" -H "$T" -H 'Content-Type: application/json' -d '{"label":"revoke-ci-2"}' | jqget "['id']")"
PU1="m$MID1"
echo "machines: $MID1 (session $PU1) and $MID2"

echo "== simulate a completed login for machine 1: live engine session + durable credential =="
expect_status 200 "$(inet PUT "http://localhost:9000/sessions/$PU1" '{"proxyPassword":"pw1","accountProxy":""}')" "engine session registered"
CRED="{\"proxyUser\":\"$PU1\",\"proxyPassword\":\"pw1\",\"accountProxy\":\"\",\"realAccess\":\"real-access-1\",\"realRefresh\":\"real-refresh-1\",\"fakeAccess\":\"fake-access-1\",\"fakeRefresh\":\"fake-refresh-1\",\"expiresAt\":9999999999}"
expect_status 200 "$(inet POST http://backend:8080/internal/credential/session "$CRED")" "durable credential planted"
expect_status 200 "$(inet GET "http://localhost:9000/sessions/$PU1/login")" "engine serves the session"
expect_status 200 "$(inet GET "http://backend:8080/internal/credential/session/$PU1")" "credential row readable"

echo "== DELETE revokes: engine session dropped + durable credential gone =="
CODE="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$GW/machine/$MID1" -H "$T")"
[ "$CODE" = 204 ] || { echo "FAIL: DELETE /machine/$MID1 returned $CODE, want 204"; exit 1; }
expect_status 404 "$(inet GET "http://localhost:9000/sessions/$PU1/login")" "engine session removed"
expect_status 404 "$(inet GET "http://backend:8080/internal/credential/session/$PU1")" "credential row gone"
expect_status 404 "$(inet POST http://backend:8080/internal/credential/session "$CRED")" "racing credential write refused"

echo "== revocation survives an engine restart (no resurrection from the DB reload) =="
docker compose restart proxy-engine >/dev/null 2>&1
for _ in $(seq 1 30); do
  if inet GET http://localhost:9000/health 2>/dev/null | grep -q '^200'; then break; fi
  sleep 2
done
expect_status 404 "$(inet GET "http://localhost:9000/sessions/$PU1/login")" "revoked session NOT reloaded after restart"

echo "== a failed engine drop fails the DELETE honestly (502, nothing deleted) =="
docker compose stop proxy-engine >/dev/null 2>&1
CODE="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$GW/machine/$MID2" -H "$T")"
[ "$CODE" = 502 ] || { echo "FAIL: DELETE with the engine down returned $CODE, want 502"; docker compose start proxy-engine >/dev/null 2>&1 || true; exit 1; }
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$GW/machine/$MID2" -H "$T")"
[ "$CODE" = 200 ] || { echo "FAIL: machine $MID2 should have survived the failed revoke (got $CODE)"; docker compose start proxy-engine >/dev/null 2>&1 || true; exit 1; }
echo "ok: DELETE -> 502 and the machine is still there"

echo "== the retry after the engine returns completes the revocation =="
docker compose start proxy-engine >/dev/null 2>&1
for _ in $(seq 1 30); do
  if inet GET http://localhost:9000/health 2>/dev/null | grep -q '^200'; then break; fi
  sleep 2
done
CODE="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$GW/machine/$MID2" -H "$T")"
[ "$CODE" = 204 ] || { echo "FAIL: retried DELETE returned $CODE, want 204"; exit 1; }
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$GW/machine/$MID2" -H "$T")"
[ "$CODE" = 404 ] || { echo "FAIL: machine $MID2 still present after the retried DELETE (got $CODE)"; exit 1; }

echo "PASS: revocation is reliable — confirmed drop, durable, restart-proof, and honest on failure"
