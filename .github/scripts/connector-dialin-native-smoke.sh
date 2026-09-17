#!/usr/bin/env bash
# Native connector dial-in smoke: same assertion as connector-dialin-smoke.sh (a machine ccproxy
# cannot SSH into installs the connector from the served install script, dials OUT, and is
# recognised online) but run on the REAL target OS itself — no Debian "fake machine" container,
# because this is what actually differs per platform: install.sh vs install.ps1, ~/.local/bin vs
# %AppData%, enroll/run behaving the same everywhere since main.go's connector logic is shared
# Go code (only service.go/terminal are platform-specific, and neither is exercised here).
#
# Run on windows-latest/macos-latest runners against a backend exposed by connector-dialin's
# cloudflared tunnel (no cross-runner Docker networking exists — see build.yml's comment on the
# server/client job split). $1 = gateway base URL (e.g. https://xxxx.trycloudflare.com/ccproxy).
set -eu

GW="$1"
BIN="$2" # absolute path the caller expects the installed binary to land at

jqget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "== create a connector-mode machine (no host) via the API =="
SUPER="${CCPROXY_SUPERADMIN_PASSWORD:?}"
TOK="$(curl -fsS -X POST "$GW/superadmin/login" -H 'Content-Type: application/json' -d "{\"password\":\"$SUPER\"}" | jqget "['token']")"
A="Authorization: Bearer $TOK"
TID="$(curl -fsS -X POST "$GW/tenant" -H "$A" -H 'Content-Type: application/json' -d '{"name":"conn-ci-native"}' | jqget "['id']")"
TSEC="$(curl -fsS -X POST "$GW/tenant/$TID/secret" -H "$A" -H 'Content-Type: application/json' -d '{}' | jqget "['secret']")"
CREATE="$(curl -fsS -X POST "$GW/machine" -H "Authorization: Bearer $TSEC" -H 'Content-Type: application/json' -d '{"label":"conn-ci-native"}')"
echo "create response: $CREATE"
MID="$(printf '%s' "$CREATE" | jqget "['id']")"
DTOKEN="$(printf '%s' "$CREATE" | jqget "['deviceToken']")"
if [ -z "$DTOKEN" ] || [ "$DTOKEN" = "None" ]; then echo "FAIL: no device token issued"; exit 1; fi
echo "machine id=$MID deviceToken=${DTOKEN:0:6}..."

test -x "$BIN" || { echo "FAIL: expected installed binary at $BIN"; ls -la "$(dirname "$BIN")" || true; exit 1; }

echo "== enrol with the device token, then run the resident connector =="
"$BIN" enroll --token "$DTOKEN"
"$BIN" run >connector-run.log 2>&1 &
RUN_PID=$!
trap 'kill "$RUN_PID" >/dev/null 2>&1 || true' EXIT

echo "== the backend recognises the machine online =="
online=""
for _ in $(seq 1 30); do
  online="$(curl -fsS "$GW/machine/$MID" -H "Authorization: Bearer $TSEC" | jqget "['online']")"
  [ "$online" = "True" ] && break
  sleep 2
done
if [ "$online" != "True" ]; then
  echo "FAIL: machine never came online (last online=$online)"
  echo "--- connector-run.log ---"; cat connector-run.log || true
  exit 1
fi
echo "PASS: native connector ($BIN) dialed in and the backend reports the machine online"
