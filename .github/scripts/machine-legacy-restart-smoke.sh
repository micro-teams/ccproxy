#!/usr/bin/env bash
# Legacy direct-proxy restart resilience: a machine that never re-logged-in since the MultiPath
# local-proxy rollout still dials backend:3128 directly with its proxy credentials embedded in
# HTTPS_PROXY (the pre-2026-09 scheme) — this is the majority of already-enrolled prod machines, and
# it gets ZERO code changes from the MultiPath work. Prove that path keeps working, and recovers
# within a bounded window, across a backend restart. Reproduces the shape of a prod incident
# (2026-09-15): a live machine on this exact path saw every request ECONNRESET for several minutes
# after a backend restart; root cause was never pinned down, so this guards against a regression in
# restart-recovery time going unnoticed. No real Anthropic account needed: like
# machine-revoke-smoke.sh, a SESSION credential is planted directly in Postgres — the same table
# SessionStore.persist itself writes through — so real CONNECT + TLS + MITM traffic flows without an
# interactive login.
set -euo pipefail

GW="http://localhost:$(grep -E '^NGINX_HTTP_PORT=' .env | cut -d= -f2 || echo 80)/ccproxy"
SUPER="$(grep -E '^SUPERADMIN_PASSWORD=' .env | cut -d= -f2)"
PROXY_PORT="$(grep -E '^ENGINE_PROXY_PORT=' .env | cut -d= -f2 || echo 3128)"

jqget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

psql_ccproxy() {
  docker compose exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Atc \"$1\""
}

echo "== provision a tenant + one hostless machine =="
TOK="$(curl -s -X POST "$GW/superadmin/login" -H 'Content-Type: application/json' -d "{\"password\":\"$SUPER\"}" | jqget "['token']")"
A="Authorization: Bearer $TOK"
TID="$(curl -s -X POST "$GW/tenant" -H "$A" -H 'Content-Type: application/json' -d '{"name":"legacy-restart-ci"}' | jqget "['id']")"
TSEC="$(curl -s -X POST "$GW/tenant/$TID/secret" -H "$A" -H 'Content-Type: application/json' -d '{}' | jqget "['secret']")"
T="Authorization: Bearer $TSEC"
MID="$(curl -s -X POST "$GW/machine" -H "$T" -H 'Content-Type: application/json' -d '{"label":"legacy-restart-ci"}' | jqget "['id']")"
PU="m$MID"
PW="legacypw$MID"
echo "machine: $MID (session $PU)"

echo "== simulate a completed old-style login: plant a durable SESSION credential row directly =="
CRED_ID="$(psql_ccproxy "SELECT (extract(epoch from now())*1000)::bigint;")"
psql_ccproxy "INSERT INTO ccproxy.credential (id, scope, cred_key, proxy_password, account_proxy, real_access, real_refresh, fake_access, fake_refresh, expires_at, created_at, updated_at) VALUES ($CRED_ID,'SESSION','$PU','$PW','','real-access-legacy','real-refresh-legacy','fake-access-legacy','fake-refresh-legacy',9999999999,now(),now());" >/dev/null
ROWS="$(psql_ccproxy "SELECT count(*) FROM ccproxy.credential WHERE scope='SESSION' AND cred_key='$PU' AND deleted_at IS NULL;")"
[ "$ROWS" = "1" ] || { echo "FAIL: credential row not planted (count=$ROWS)"; exit 1; }
echo "ok: credential row planted"

# Talk to backend:3128 exactly the way a legacy machine's HTTPS_PROXY does: direct CONNECT with
# Proxy-Authorization, TLS-terminated by the MITM CA, a real /v1/messages-shaped request. The
# upstream call to the real api.anthropic.com will fail auth (the planted "real-access-legacy" token
# is not a real credential) — that is fine and expected. We are only asserting that ccproxy's OWN
# leg (CONNECT tunnel -> TLS -> MitmHandler -> some HTTP response) does not reset the connection.
probe() {
  curl -sS -o /dev/null -w '%{http_code}' --max-time 15 \
    -x "http://$PU:$PW@localhost:$PROXY_PORT" \
    --cacert keys/ca.crt \
    -X POST "https://api.anthropic.com/v1/messages" \
    -H "authorization: Bearer whatever" \
    -H "anthropic-version: 2023-06-01" \
    -H "content-type: application/json" \
    -d '{"model":"claude-sonnet-5","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}' \
    2>&1
}

echo "== baseline: the legacy direct path answers with a real HTTP status, not a reset =="
CODE="$(probe)"
case "$CODE" in
  [0-9][0-9][0-9]) echo "ok: baseline got HTTP $CODE" ;;
  *) echo "FAIL: baseline probe did not get an HTTP status (got '$CODE')"; exit 1 ;;
esac

echo "== restart backend =="
docker compose restart backend >/dev/null 2>&1
for _ in $(seq 1 60); do
  h="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q backend)" 2>/dev/null || true)"
  [ "$h" = "healthy" ] && break
  sleep 2
done
[ "$h" = "healthy" ] || { echo "FAIL: backend never became healthy after restart"; exit 1; }
echo "backend healthy again"

echo "== recovery: the legacy path must give stable HTTP responses again within 30s =="
deadline=$((SECONDS + 30))
ok_streak=0
last=""
while [ "$SECONDS" -lt "$deadline" ]; do
  last="$(probe)"
  case "$last" in
    [0-9][0-9][0-9]) ok_streak=$((ok_streak + 1)) ;;
    *) ok_streak=0 ;;
  esac
  # 3 consecutive clean HTTP responses, not just one lucky hit.
  [ "$ok_streak" -ge 3 ] && break
  sleep 1
done
[ "$ok_streak" -ge 3 ] || { echo "FAIL: legacy direct path never stabilized within 30s (last probe: '$last')"; exit 1; }

echo "PASS: legacy direct-proxy path answers before restart and recovers within 30s after"
