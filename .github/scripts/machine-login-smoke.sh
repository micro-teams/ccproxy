#!/usr/bin/env bash
# Connector login compat smoke: spin a plain Debian "machine" (with sshd + Claude Code), let the
# backend BOOTSTRAP it onto the connector — POST /machine with a host makes the backend SSH in ONCE
# to `curl <base>/install.sh | sh && ccproxy-connector connect … --token …`, which installs the
# connector and dials back in — then drive login OVER that connector until AWAITING_CODE (Claude
# launched via the shared claude.js applet in login mode, an OAuth URL captured). No real Anthropic
# account: it stops where a human would paste the code. Run from an up compose bundle dir (.env, keys/).
#
# It exercises the login drive on one machine: a fresh first-run wizard, an already-onboarded /login,
# a newapi-in-settings machine (login must reach official anyway, via the login-only settings file),
# engine-restart self-heal, and the DB token-persistence round-trip. The whole thing goes over the
# connector — the backend no longer SSH-drives tmux.
#
# Usage: machine-login-smoke.sh <install-spec>
#   install-spec = "installer"      -> curl https://claude.ai/install.sh | bash   (latest, unpinned)
#                  "npm:<version>"  -> npm i -g @anthropic-ai/claude-code@<version>
set -euo pipefail

INSTALL="${1:-installer}"
GW="http://localhost:$(grep -E '^NGINX_HTTP_PORT=' .env | cut -d= -f2 || echo 80)/ccproxy"
MACHINE="ccproxy-testmachine"
# Put the test machine on whatever network the running backend is on (robust to stack naming).
BACKEND="$(docker ps --filter 'name=backend' --format '{{.Names}}' | head -1)"
NET="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$BACKEND")"
PUBKEY="$(cat keys/operator.pub)"

jqget() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

cleanup() { docker rm -f "$MACHINE" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== [$INSTALL] spin machine container on $NET =="
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

echo "== install Claude Code ($INSTALL) =="
case "$INSTALL" in
  npm:*)
    VER="${INSTALL#npm:}"
    docker exec "$MACHINE" bash -c "
      curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null 2>&1
      apt-get install -y -qq nodejs >/dev/null
      npm i -g @anthropic-ai/claude-code@${VER} >/dev/null 2>&1" ;;
  *)
    docker exec "$MACHINE" bash -lc "curl -fsSL https://claude.ai/install.sh | bash >/dev/null 2>&1" ;;
esac
echo -n "claude version on machine: "
docker exec "$MACHINE" bash -lc 'PATH="$HOME/.local/bin:$PATH" claude --version' ||
  { echo "claude not installed"; exit 1; }

echo "== configure super-admin resources =="
SUPER="$(grep -E '^SUPERADMIN_PASSWORD=' .env | cut -d= -f2)"
TOK="$(curl -s -X POST "$GW/superadmin/login" -H 'Content-Type: application/json' -d "{\"password\":\"$SUPER\"}" | jqget "['token']")"
A="Authorization: Bearer $TOK"
curl -s -X POST "$GW/account" -H "$A" -H 'Content-Type: application/json' -d '{"email":"compat@ci","remark":"ci"}' >/dev/null
TID="$(curl -s -X POST "$GW/tenant" -H "$A" -H 'Content-Type: application/json' -d '{"name":"ci"}' | jqget "['id']")"
TSEC="$(curl -s -X POST "$GW/tenant/$TID/secret" -H "$A" -H 'Content-Type: application/json' -d '{}' | jqget "['secret']")"
OID="$(curl -s -X POST "$GW/login-operator" -H "$A" -H 'Content-Type: application/json' -d '{"name":"ci"}' | jqget "['id']")"
OSEC="$(curl -s -X POST "$GW/login-operator/$OID/secret" -H "$A" -H 'Content-Type: application/json' -d '{}' | jqget "['secret']")"

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

expect_awaiting_code() { # $1=machineId  $2=label
  local lrid body ls url
  lrid="$(curl -s -X POST "$GW/machine/$1/login" -H "Authorization: Bearer $TSEC" | jqget "['id']")"
  for _ in $(seq 1 60); do
    body="$(curl -s "$GW/login-request/$lrid" -H "Authorization: Bearer $OSEC")"
    ls="$(printf '%s' "$body" | jqget "['status']")"
    if [ "$ls" = "awaitingCode" ]; then
      url="$(printf '%s' "$body" | jqget "['oauthUrl'] or ''")"
      case "$url" in
        # The URL must carry a state= param — a wrapped/truncated capture drops it (see the
        # connector login pane width). Assert both "oauth" and "state=".
        *oauth*state=*|*state=*oauth*) echo "PASS $2: awaitingCode with a state-carrying URL"
                 curl -s -X POST "$GW/login-request/$lrid/cancel" -H "Authorization: Bearer $OSEC" >/dev/null 2>&1 || true
                 return 0 ;;
        *oauth*) echo "FAIL $2: OAuth URL is missing its state= param: ${url:0:80}"; return 1 ;;
        *) echo "FAIL $2: awaitingCode but no oauth URL"; return 1 ;;
      esac
    fi
    [ "$ls" = "failed" ] && { echo "FAIL $2: prepare failed:"; printf '%s' "$body" | jqget "['error']"; return 1; }
    sleep 3
  done
  echo "FAIL $2: never reached awaitingCode (last=$ls)"; return 1
}

# Seed a settings.json the tenant/user co-owns, with a newapi gateway override, for round 2b. The
# connector never touches this file at provision time now; login uses a separate login-only settings
# file, so this must still be here (untouched) after an incomplete login.
docker exec -i "$MACHINE" bash -lc 'mkdir -p ~/.claude; cat > ~/.claude/settings.json' <<'JSON'
{"env":{"ANTHROPIC_BASE_URL":"https://newapi.example.invalid","ANTHROPIC_AUTH_TOKEN":"sk-newapi-fake"},"theme":"dark"}
JSON

echo "== register: the backend SSH-bootstraps the connector, machine dials in =="
MID="$(curl -s -X POST "$GW/machine" -H "Authorization: Bearer $TSEC" -H 'Content-Type: application/json' \
        -d "{\"host\":\"$MACHINE\",\"label\":\"ci\"}" | jqget "['id']")"
wait_status "$MID" awaitingLogin || exit 1
online="$(curl -s "$GW/machine/$MID" -H "Authorization: Bearer $TSEC" | jqget "['online']")"
[ "$online" = "True" ] || { echo "FAIL: connector never dialed in (online=$online)"; exit 1; }
# The connector-first design never writes a system-wide global proxy (that was the old footgun that
# left a re-imported machine pinned to a dead identity).
docker exec "$MACHINE" test ! -e /etc/profile.d/ccproxy-proxy.sh ||
  { echo "FAIL: /etc/profile.d/ccproxy-proxy.sh must NOT exist"; exit 1; }
echo "bootstrapped onto the connector, online OK"

echo "== round 1: fresh machine (first-run wizard path) =="
expect_awaiting_code "$MID" "round1-fresh-wizard" || exit 1

echo "== round 2: already-onboarded machine, expect the /login path =="
docker exec "$MACHINE" bash -lc 'python3 -c "import json,os;p=os.path.expanduser(\"~/.claude.json\");d=json.load(open(p)) if os.path.exists(p) and os.path.getsize(p) else {};d[\"hasCompletedOnboarding\"]=True;json.dump(d,open(p,\"w\"))"'
expect_awaiting_code "$MID" "round2-onboarded-login" || exit 1

echo "== round 2b: newapi in settings.json — login reaches official anyway, real file untouched =="
docker exec "$MACHINE" bash -lc 'python3 -c "import json,os;print(json.load(open(os.path.expanduser(\"~/.claude/settings.json\")))[\"env\"][\"ANTHROPIC_BASE_URL\"])"' \
  | grep -q 'newapi.example.invalid' || { echo "FAIL: precondition, newapi not in settings.json"; exit 1; }
expect_awaiting_code "$MID" "round2b-newapi-settings-still-official" || exit 1
docker exec "$MACHINE" bash -lc 'python3 -c "import json,os;print(json.load(open(os.path.expanduser(\"~/.claude/settings.json\")))[\"env\"].get(\"ANTHROPIC_BASE_URL\",\"\"))"' \
  | grep -q 'newapi.example.invalid' ||
  { echo "FAIL: an incomplete login modified the real settings.json (should be untouched)"; exit 1; }
echo "PASS round2b: login reached official; real settings.json newapi untouched"

echo "== round 3: engine restart self-heal (lazy session fetch, no re-bootstrap) =="
# The engine keeps sessions in memory; restarting wipes them and it refetches from the backend on the
# first connection. The connector's control link (to the backend) is untouched by an engine restart,
# so the machine stays online — a fresh login must still reach awaitingCode.
docker compose restart proxy-engine >/dev/null 2>&1
for _ in $(seq 1 20); do
  h="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q proxy-engine)" 2>/dev/null || true)"
  [ "$h" = "healthy" ] && break
  sleep 2
done
expect_awaiting_code "$MID" "round3-engine-restart-selfheal" || exit 1

echo "== round 4: token-persistence round-trip through the backend DB =="
# Persist under a REAL machine's proxy user: the credential-ingest write now refuses a user with no
# live machine (the revoked-ticket guard), so a synthetic key would 404. Machine $MID's proxy user is
# m$MID (MachineService sets proxyUser = "m<id>").
PU="m$MID"
POUT="$(docker compose exec -T -w /app -e PU="$PU" proxy-engine python3 - <<'PY'
import os, ccproxy_engine as e
u = os.environ["PU"]
s = e.REGISTRY.put(u, "pw", "http://egress-proxy:7890")
s.real_access, s.real_refresh = "RA", "RR"
s.fake_access, s.fake_refresh, s.expires_at = "FA", "FR", 999
e.persist_session(u, s)              # write-through to the backend DB
e.REGISTRY._by_user.clear()          # simulate a restart wiping memory
e.load_all_from_db()                 # reload from the DB (source of truth)
s2 = e.REGISTRY.get(u)
ok = bool(s2 and s2.real_refresh == "RR" and s2.fake_access == "FA" and s2.expires_at == 999)
print("PERSIST_OK" if ok else "PERSIST_FAIL")
PY
)"
docker compose exec -T -e PU="$PU" postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DELETE FROM ccproxy.credential WHERE scope='\''SESSION'\'' AND cred_key='\''$PU'\'';"' \
  >/dev/null 2>&1 || true
case "$POUT" in
  *PERSIST_OK*) echo "PASS round4-token-persistence" ;;
  *) echo "FAIL round4-token-persistence: $POUT"; exit 1 ;;
esac

echo "ALL PASS [$INSTALL]"
