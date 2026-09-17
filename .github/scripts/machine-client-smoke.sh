#!/usr/bin/env bash
# Unified machine-client smoke test: runs the SAME scenarios on whatever real OS this script is
# invoked on (linux/windows/macos) — no Debian "fake machine" container standing in for a platform,
# because this now runs as the actual target platform. Replaces the docker-exec-based
# connector-dialin-smoke.sh and machine-setup-token-smoke.sh for the client side; the two scenarios
# that need direct Docker/Postgres access (legacy-restart, revoke) stay server-side, since they test
# backend/dataplane behavior that has nothing to do with client OS.
#
# $1 = gateway base URL (through cloudflared for windows/macos, http://localhost/ccproxy on the
#      linux leg, which runs on the same runner as the server job)
# $2 = path to the already-installed connector binary
# Env: CCPROXY_SUPERADMIN_PASSWORD
set -eu

GW="$1"
BIN="$2"

jqget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

test -x "$BIN" || { echo "FAIL: expected installed binary at $BIN"; ls -la "$(dirname "$BIN")" || true; exit 1; }

RUN_PID=""
cleanup() { [ -z "$RUN_PID" ] || kill "$RUN_PID" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# ---------------------------------------------------------------------------------------------
echo "== [dial-in] create a connector-mode machine (no host) via the API =="
SUPER="${CCPROXY_SUPERADMIN_PASSWORD:?}"
TOK="$(curl -fsS -X POST "$GW/superadmin/login" -H 'Content-Type: application/json' -d "{\"password\":\"$SUPER\"}" | jqget "['token']")"
A="Authorization: Bearer $TOK"
TID="$(curl -fsS -X POST "$GW/tenant" -H "$A" -H 'Content-Type: application/json' -d '{"name":"conn-ci-native"}' | jqget "['id']")"
TSEC="$(curl -fsS -X POST "$GW/tenant/$TID/secret" -H "$A" -H 'Content-Type: application/json' -d '{}' | jqget "['secret']")"
T="Authorization: Bearer $TSEC"
CREATE="$(curl -fsS -X POST "$GW/machine" -H "$T" -H 'Content-Type: application/json' -d '{"label":"conn-ci-native"}')"
MID="$(printf '%s' "$CREATE" | jqget "['id']")"
DTOKEN="$(printf '%s' "$CREATE" | jqget "['deviceToken']")"
if [ -z "$DTOKEN" ] || [ "$DTOKEN" = "None" ]; then echo "FAIL: no device token issued"; exit 1; fi
echo "machine id=$MID deviceToken=${DTOKEN:0:6}..."

echo "== [dial-in] enrol with the device token, then run the resident connector =="
"$BIN" enroll "$GW" --token "$DTOKEN"
"$BIN" run >connector-run.log 2>&1 &
RUN_PID=$!

online=""
for _ in $(seq 1 30); do
  online="$(curl -fsS "$GW/machine/$MID" -H "$T" | jqget "['online']")"
  [ "$online" = "True" ] && break
  sleep 2
done
if [ "$online" != "True" ]; then
  echo "FAIL: machine never came online (last online=$online)"
  echo "--- connector-run.log ---"; cat connector-run.log || true
  exit 1
fi
echo "PASS: connector dialed in and the backend reports the machine online"

# ---------------------------------------------------------------------------------------------
echo "== [setup-token] bind a setup-token to this machine's tenant/account =="
AID="$(curl -fsS -X POST "$GW/account" -H "$A" -H 'Content-Type: application/json' -d '{"email":"setuptoken-native@ci","remark":"ci setup-token fast path (native)"}' | jqget "['id']")"
curl -fsS -X PUT "$GW/account/$AID/setup-token" -H "$A" -H 'Content-Type: application/json' \
  -d '{"oauthToken":"sk-ant-oat01-ciFakeSetupTokenNotReal0000000000000000000000000000"}' >/dev/null

# Reproduces the actual prod scenario (found 2026-09-15): a machine that was ALREADY provisioned
# before the MultiPath local-proxy scheme existed, with an old-style settings.json (proxy
# credentials embedded straight in HTTPS_PROXY). configureOfficialProxy's read-modify-write over an
# EXISTING file is the path that matters — a fresh machine with none does not reproduce this.
echo "== [setup-token] seed an old-style settings.json (pre-MultiPath direct-proxy-with-credentials) =="
mkdir -p "$HOME/.claude"
cat >"$HOME/.claude/settings.json" <<'JSON'
{"theme":"dark","env":{"HTTPS_PROXY":"http://oldm:oldpw@old-ccproxy-host:3128","https_proxy":"http://oldm:oldpw@old-ccproxy-host:3128","HTTP_PROXY":"http://oldm:oldpw@old-ccproxy-host:3128","http_proxy":"http://oldm:oldpw@old-ccproxy-host:3128","CLAUDE_CODE_OAUTH_TOKEN":"sk-ant-oat01-preexisting-fake-token"}}
JSON

echo "== [setup-token] start login: bound account has a setup-token, so this must auto-complete =="
curl -fsS -X POST "$GW/machine/$MID/login" -H "$T" >/dev/null

ls=""
for _ in $(seq 1 30); do
  ls="$(curl -fsS "$GW/machine/$MID" -H "$T" | jqget "['status']")"
  [ "$ls" = "ready" ] && break
  [ "$ls" = "error" ] && { echo "FAIL: machine went to error"; curl -fsS "$GW/machine/$MID" -H "$T"; exit 1; }
  sleep 2
done
[ "$ls" = "ready" ] || { echo "FAIL: machine never reached ready (last=$ls)"; exit 1; }
hasCred="$(curl -fsS "$GW/machine/$MID" -H "$T" | jqget "['hasCredential']")"
[ "$hasCred" = "True" ] || { echo "FAIL: machine ready but hasCredential=$hasCred"; exit 1; }
echo "PASS: machine reached ready with hasCredential via setup-token, no operator involved"

echo "== [setup-token] verify the connector-side config actually landed on this machine =="
CFG_JSON="$(cat "$HOME/.claude/settings.json" 2>/dev/null || echo '{}')"
HTTPS_PROXY_VAL="$(printf '%s' "$CFG_JSON" | jqget "['env']['HTTPS_PROXY']")"
case "$HTTPS_PROXY_VAL" in
  http://127.0.0.1:*)
    echo "PASS: HTTPS_PROXY points at the local proxy ($HTTPS_PROXY_VAL)" ;;
  *)
    echo "FAIL: HTTPS_PROXY is not the local split-proxy URL: got '$HTTPS_PROXY_VAL', want http://127.0.0.1:<port>"
    echo "full settings.json: $CFG_JSON"
    exit 1 ;;
esac
case "$HTTPS_PROXY_VAL" in
  *@*) echo "FAIL: HTTPS_PROXY still carries embedded credentials: $HTTPS_PROXY_VAL"; exit 1 ;;
esac

# Always $HOME/.config/ccproxy-connector regardless of OS — see connector/localproxy.go's
# credentialPath() comment: this file is deliberately NOT under os.UserConfigDir() (that's
# config.json's path, and it DOES vary — Library/Application Support on Darwin, %AppData% on
# Windows; see install.sh's platform split).
if ! CRED_JSON="$(cat "$HOME/.config/ccproxy-connector/proxy-credential.json" 2>&1)"; then
  echo "FAIL: proxy-credential.json was never written: $CRED_JSON"
  exit 1
fi
PU="$(printf '%s' "$CRED_JSON" | jqget "['proxyUser']")"
PP="$(printf '%s' "$CRED_JSON" | jqget "['proxyPassword']")"
if [ -z "$PU" ] || [ -z "$PP" ]; then
  echo "FAIL: proxy-credential.json missing proxyUser/proxyPassword: $CRED_JSON"
  exit 1
fi
echo "PASS: proxy-credential.json present with proxyUser=$PU"

echo "ALL PASS [dial-in + setup-token fast path, native]"
