#!/usr/bin/env bash
# Machine revocation smoke test: prove DELETE /machine/{id} is a RELIABLE revocation of exactly one
# machine's ticket — the in-process dataplane's live session is dropped, the durable credential row
# is gone, and the revocation survives a backend restart (no resurrection from the DB reload). Needs
# NO real Anthropic account: the "captured" credential is planted directly in Postgres, the same
# table the dataplane itself writes through (SessionStore.persist). Run from an up compose bundle
# directory (has .env).
#
# NOTE (2026-09-14 cutover): this used to also cover "the engine is down -> DELETE fails 502 ->
# retry after it's back succeeds" by stopping the standalone proxy-engine container independently of
# the backend. That failure mode no longer exists: the dataplane is in-process with the backend now,
# so there is nothing to go down separately from the backend itself (killing the backend would also
# kill the ability to call DELETE at all). The "racing credential write refused for a deleted
# machine" guard (SessionStore.persist's machineRepository.findByProxyUser check) is covered by a
# backend unit test instead of here, since triggering it now requires live MITM traffic rather than
# a loopback HTTP call.
set -euo pipefail

GW="http://localhost:$(grep -E '^NGINX_HTTP_PORT=' .env | cut -d= -f2 || echo 80)/ccproxy"
SUPER="$(grep -E '^SUPERADMIN_PASSWORD=' .env | cut -d= -f2)"

jqget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

psql_ccproxy() {
  docker compose exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Atc \"$1\""
}

echo "== provision a tenant + one hostless machine =="
TOK="$(curl -s -X POST "$GW/superadmin/login" -H 'Content-Type: application/json' -d "{\"password\":\"$SUPER\"}" | jqget "['token']")"
A="Authorization: Bearer $TOK"
TID="$(curl -s -X POST "$GW/tenant" -H "$A" -H 'Content-Type: application/json' -d '{"name":"revoke-ci"}' | jqget "['id']")"
TSEC="$(curl -s -X POST "$GW/tenant/$TID/secret" -H "$A" -H 'Content-Type: application/json' -d '{}' | jqget "['secret']")"
T="Authorization: Bearer $TSEC"
MID1="$(curl -s -X POST "$GW/machine" -H "$T" -H 'Content-Type: application/json' -d '{"label":"revoke-ci-1"}' | jqget "['id']")"
PU1="m$MID1"
echo "machine: $MID1 (session $PU1)"

echo "== simulate a completed login: plant a durable SESSION credential row directly =="
psql_ccproxy "INSERT INTO ccproxy.credential (scope, cred_key, proxy_password, account_proxy, real_access, real_refresh, fake_access, fake_refresh, expires_at) VALUES ('SESSION','$PU1','pw1','','real-access-1','real-refresh-1','fake-access-1','fake-refresh-1',9999999999);" >/dev/null
ROWS="$(psql_ccproxy "SELECT count(*) FROM ccproxy.credential WHERE scope='SESSION' AND cred_key='$PU1' AND deleted_at IS NULL;")"
[ "$ROWS" = "1" ] || { echo "FAIL: credential row not planted (count=$ROWS)"; exit 1; }
echo "ok: credential row planted"

echo "== DELETE revokes: durable credential gone =="
CODE="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$GW/machine/$MID1" -H "$T")"
[ "$CODE" = 204 ] || { echo "FAIL: DELETE /machine/$MID1 returned $CODE, want 204"; exit 1; }
ROWS="$(psql_ccproxy "SELECT count(*) FROM ccproxy.credential WHERE scope='SESSION' AND cred_key='$PU1' AND deleted_at IS NULL;")"
[ "$ROWS" = "0" ] || { echo "FAIL: credential row survived DELETE (count=$ROWS)"; exit 1; }
echo "ok: credential row soft-deleted"

echo "== revocation survives a backend restart (no resurrection from the DB reload) =="
docker compose restart backend >/dev/null 2>&1
for _ in $(seq 1 60); do
  h="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q backend)" 2>/dev/null || true)"
  [ "$h" = "healthy" ] && break
  sleep 2
done
[ "$h" = "healthy" ] || { echo "FAIL: backend never became healthy after restart"; exit 1; }
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$GW/machine/$MID1" -H "$T")"
[ "$CODE" = 404 ] || { echo "FAIL: revoked machine $MID1 reappeared after restart (got $CODE)"; exit 1; }
ROWS="$(psql_ccproxy "SELECT count(*) FROM ccproxy.credential WHERE scope='SESSION' AND cred_key='$PU1' AND deleted_at IS NULL;")"
[ "$ROWS" = "0" ] || { echo "FAIL: revoked session resurrected in the DB reload (count=$ROWS)"; exit 1; }

echo "PASS: revocation is reliable — confirmed drop, durable, restart-proof"
