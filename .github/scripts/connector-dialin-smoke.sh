#!/usr/bin/env bash
# Connector dial-in smoke test: prove a machine ccproxy CANNOT SSH into can install the connector
# from the served install.sh, dial OUT to the control plane, and be recognised online — the whole new
# enrolment + transport + hub stack, end to end, on a real machine container. Needs NO real Anthropic
# account and no Claude Code: it stops once the backend reports the machine online (the interactive
# login drive over this same link is a faithful port of the SSH login, exercised by the build + the
# driver's own tests). Run from an up compose bundle directory (has .env).
set -euo pipefail

MACHINE="ccproxy-connector-testmachine"
GW="http://localhost:$(grep -E '^NGINX_HTTP_PORT=' .env | cut -d= -f2 || echo 80)/ccproxy"
# The machine reaches the control plane over the compose network, through nginx (which strips the
# /ccproxy prefix and upgrades the WebSocket). Service DNS resolves on the backend's network.
NGB="http://nginx/ccproxy"
BACKEND="$(docker ps --filter 'name=backend' --format '{{.Names}}' | head -1)"
NET="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$BACKEND")"

jqget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }
cleanup() { docker rm -f "$MACHINE" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== spin a plain machine container on $NET (no SSH, no Claude) =="
docker rm -f "$MACHINE" >/dev/null 2>&1 || true
docker run -d --name "$MACHINE" --hostname "$MACHINE" --network "$NET" debian:13 sleep infinity >/dev/null
docker exec "$MACHINE" bash -c '
  set -e; export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl procps >/dev/null'

echo "== create a connector-mode machine (no host) via the API =="
SUPER="$(grep -E '^SUPERADMIN_PASSWORD=' .env | cut -d= -f2)"
TOK="$(curl -s -X POST "$GW/superadmin/login" -H 'Content-Type: application/json' -d "{\"password\":\"$SUPER\"}" | jqget "['token']")"
A="Authorization: Bearer $TOK"
TID="$(curl -s -X POST "$GW/tenant" -H "$A" -H 'Content-Type: application/json' -d '{"name":"conn-ci"}' | jqget "['id']")"
TSEC="$(curl -s -X POST "$GW/tenant/$TID/secret" -H "$A" -H 'Content-Type: application/json' -d '{}' | jqget "['secret']")"
CREATE="$(curl -s -X POST "$GW/machine" -H "Authorization: Bearer $TSEC" -H 'Content-Type: application/json' -d '{"label":"conn-ci"}')"
echo "create response: $CREATE"
MID="$(printf '%s' "$CREATE" | jqget "['id']")"
DTOKEN="$(printf '%s' "$CREATE" | jqget "['deviceToken']")"
CONNECTOR="$(printf '%s' "$CREATE" | jqget "['connector']")"
[ "$CONNECTOR" = "True" ] || { echo "FAIL: machine is not connector-mode"; exit 1; }
if [ -z "$DTOKEN" ] || [ "$DTOKEN" = "None" ]; then echo "FAIL: no device token issued"; exit 1; fi
echo "machine id=$MID connector=$CONNECTOR deviceToken=${DTOKEN:0:6}…"

echo "== install the connector on the machine from the served install.sh =="
docker exec "$MACHINE" bash -lc "curl -fsSL '$NGB/install.sh' | sh" || { echo "FAIL: install.sh failed"; exit 1; }
docker exec "$MACHINE" bash -lc 'PATH="$HOME/.local/bin:$PATH" ccproxy-connector status' || true

echo "== enrol with the device token, then run the resident connector =="
docker exec "$MACHINE" bash -lc "PATH=\"\$HOME/.local/bin:\$PATH\" ccproxy-connector enroll --token '$DTOKEN'" \
  || { echo "FAIL: enroll failed"; exit 1; }
# `run` is the foreground resident the service would launch; run it detached (no systemd in a plain
# container). It dials out and stays connected.
docker exec -d "$MACHINE" bash -lc 'PATH="$HOME/.local/bin:$PATH" ccproxy-connector run'

echo "== the backend recognises the machine online =="
online=""
for _ in $(seq 1 30); do
  online="$(curl -s "$GW/machine/$MID" -H "Authorization: Bearer $TSEC" | jqget "['online']")"
  [ "$online" = "True" ] && break
  sleep 2
done
if [ "$online" != "True" ]; then
  echo "FAIL: machine never came online (last online=$online)"
  echo "--- connector logs ---"; docker exec "$MACHINE" bash -lc 'cat /tmp/*ccproxy* 2>/dev/null; journalctl 2>/dev/null | tail -20 || true'
  echo "--- backend logs ---"; docker logs "$BACKEND" 2>&1 | tail -40
  exit 1
fi
echo "PASS: connector dialed in and the backend reports the machine online"
