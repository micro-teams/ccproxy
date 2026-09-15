#!/usr/bin/env bash
# Setup-token fast-login smoke: spin a plain Debian "machine", give its bound account a stored
# setup-token (PUT /account/{id}/setup-token), then start a login. With a setup-token bound,
# startForMachine takes ConnectorLoginOrchestrator.prepareViaSetupToken — no tmux, no OAuth URL, no
# operator — and must leave the machine fully configured for the MultiPath local-proxy split:
#   - ~/.claude/settings.json: HTTPS_PROXY (and friends) point at the LOCAL proxy
#     (http://127.0.0.1:<localProxyPort>), carrying no embedded credentials
#   - ~/.config/ccproxy-connector/proxy-credential.json: written with the machine's proxyUser/
#     proxyPassword, which the local proxy reads per-CONNECT to reattach Proxy-Authorization
# Covers a path CI never exercised: machine-login-smoke.sh only drives the interactive /login screen
# (a human pastes a code); this drives the automatic setup-token path, which is a fully separate code
# path in ConnectorLoginOrchestrator (prepareViaSetupToken vs prepare). Run from an up compose bundle
# dir (.env, keys/).
set -euo pipefail

GW="http://localhost:$(grep -E '^NGINX_HTTP_PORT=' .env | cut -d= -f2 || echo 80)/ccproxy"
MACHINE="ccproxy-setuptoken-testmachine"
BACKEND="$(docker ps --filter 'name=backend' --format '{{.Names}}' | head -1)"
NET="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$BACKEND")"
PUBKEY="$(cat keys/operator.pub)"

jqget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

cleanup() {
  local ec=$?
  if [ "$ec" -ne 0 ]; then
    echo "== [debug] connector-side diagnostics (exit $ec) =="
    docker exec "$MACHINE" bash -lc '
      echo "-- run.log --"; cat ~/.config/ccproxy-connector/run.log 2>&1 || echo "(no run.log)"
      echo "-- proxy-credential.json --"; cat ~/.config/ccproxy-connector/proxy-credential.json 2>&1 || echo "(none)"
      echo "-- settings.json --"; cat ~/.claude/settings.json 2>&1 || echo "(none)"
      echo "-- listening sockets --"; (ss -tlnp 2>&1 || netstat -tlnp 2>&1) || echo "(no ss/netstat)"
      echo "-- connector processes --"; ps aux 2>&1 | grep -i ccproxy-connector || echo "(none running)"
    ' 2>&1 || echo "(docker exec for diagnostics itself failed)"
  fi
  docker rm -f "$MACHINE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== spin machine container on $NET =="
docker rm -f "$MACHINE" >/dev/null 2>&1 || true
docker run -d --name "$MACHINE" --hostname "$MACHINE" --network "$NET" debian:13 sleep infinity >/dev/null
docker exec "$MACHINE" bash -c '
  set -e; export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq openssh-server tmux ca-certificates curl procps python3 >/dev/null
  mkdir -p /run/sshd /root/.ssh
  printf "%s\n" "'"$PUBKEY"'" > /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
  sed -i "s/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/" /etc/ssh/sshd_config
  ssh-keygen -A >/dev/null 2>&1
  /usr/sbin/sshd
'
# prepareViaSetupToken never launches an interactive Claude session, but the connector's own
# bootstrap preflight (connect --token) still requires claude to be present on the machine.
docker exec "$MACHINE" bash -lc 'curl -fsSL https://claude.ai/install.sh | bash >/dev/null 2>&1'
docker exec "$MACHINE" bash -lc 'PATH="$HOME/.local/bin:$PATH" claude --version' ||
  { echo "claude not installed"; exit 1; }

echo "== configure super-admin resources =="
SUPER="$(grep -E '^SUPERADMIN_PASSWORD=' .env | cut -d= -f2)"
TOK="$(curl -s -X POST "$GW/superadmin/login" -H 'Content-Type: application/json' -d "{\"password\":\"$SUPER\"}" | jqget "['token']")"
A="Authorization: Bearer $TOK"
AID="$(curl -s -X POST "$GW/account" -H "$A" -H 'Content-Type: application/json' -d '{"email":"setuptoken@ci","remark":"ci setup-token fast path"}' | jqget "['id']")"
curl -s -X PUT "$GW/account/$AID/setup-token" -H "$A" -H 'Content-Type: application/json' \
  -d '{"oauthToken":"sk-ant-oat01-ciFakeSetupTokenNotReal0000000000000000000000000000"}' >/dev/null
TID="$(curl -s -X POST "$GW/tenant" -H "$A" -H 'Content-Type: application/json' -d '{"name":"ci-setuptoken"}' | jqget "['id']")"
TSEC="$(curl -s -X POST "$GW/tenant/$TID/secret" -H "$A" -H 'Content-Type: application/json' -d '{}' | jqget "['secret']")"

wait_status() { # $1=machineId  $2=target
  local st=""
  for _ in $(seq 1 60); do
    st="$(curl -s "$GW/machine/$1" -H "Authorization: Bearer $TSEC" | jqget "['status']")"
    [ "$st" = "$2" ] && return 0
    [ "$st" = "error" ] && { echo "machine error:"; curl -s "$GW/machine/$1" -H "Authorization: Bearer $TSEC"; return 1; }
    sleep 2
  done
  echo "machine $1 never reached $2 (last=$st)"; return 1
}

echo "== register: the backend SSH-bootstraps the connector, machine dials in =="
MID="$(curl -s -X POST "$GW/machine" -H "Authorization: Bearer $TSEC" -H 'Content-Type: application/json' \
        -d "{\"host\":\"$MACHINE\",\"label\":\"ci-setuptoken\"}" | jqget "['id']")"
wait_status "$MID" awaitingLogin || exit 1
online="$(curl -s "$GW/machine/$MID" -H "Authorization: Bearer $TSEC" | jqget "['online']")"
[ "$online" = "True" ] || { echo "FAIL: connector never dialed in (online=$online)"; exit 1; }
echo "bootstrapped onto the connector, online OK"

# Reproduces the actual prod scenario (found 2026-09-15): a machine that was ALREADY provisioned
# before this MultiPath local-proxy scheme existed, with an old-style settings.json (proxy
# credentials embedded straight in HTTPS_PROXY). A fresh machine with no settings.json at all did
# NOT reproduce the bug — configureOfficialProxy's read-modify-write over an EXISTING file is the
# path that matters.
echo "== seed an old-style settings.json (pre-MultiPath direct-proxy-with-credentials) =="
docker exec -i "$MACHINE" bash -lc 'mkdir -p ~/.claude; cat > ~/.claude/settings.json' <<'JSON'
{"theme":"dark","env":{"HTTPS_PROXY":"http://oldm:oldpw@old-ccproxy-host:3128","https_proxy":"http://oldm:oldpw@old-ccproxy-host:3128","HTTP_PROXY":"http://oldm:oldpw@old-ccproxy-host:3128","http_proxy":"http://oldm:oldpw@old-ccproxy-host:3128","CLAUDE_CODE_OAUTH_TOKEN":"sk-ant-oat01-preexisting-fake-token"}}
JSON

echo "== start login: bound account has a setup-token, so this must auto-complete (no operator) =="
LR="$(curl -s -X POST "$GW/machine/$MID/login" -H "Authorization: Bearer $TSEC")"
echo "$LR"

for _ in $(seq 1 30); do
  ls="$(curl -s "$GW/machine/$MID" -H "Authorization: Bearer $TSEC" | jqget "['status']")"
  [ "$ls" = "ready" ] && break
  [ "$ls" = "error" ] && { echo "FAIL: machine went to error"; curl -s "$GW/machine/$MID" -H "Authorization: Bearer $TSEC"; exit 1; }
  sleep 2
done
[ "$ls" = "ready" ] || { echo "FAIL: machine never reached ready (last=$ls)"; exit 1; }
hasCred="$(curl -s "$GW/machine/$MID" -H "Authorization: Bearer $TSEC" | jqget "['hasCredential']")"
[ "$hasCred" = "True" ] || { echo "FAIL: machine ready but hasCredential=$hasCred"; exit 1; }
echo "PASS: machine reached ready with hasCredential via setup-token, no operator involved"

echo "== verify the connector-side config actually landed on the machine =="
CFG_JSON="$(docker exec "$MACHINE" bash -lc 'cat ~/.claude/settings.json 2>/dev/null || echo "{}"')"
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

if ! CRED_JSON="$(docker exec "$MACHINE" bash -lc 'cat ~/.config/ccproxy-connector/proxy-credential.json 2>&1')"; then
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

echo "ALL PASS [setup-token fast path]"
