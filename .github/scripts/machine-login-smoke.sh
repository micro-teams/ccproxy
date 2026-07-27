#!/usr/bin/env bash
# Compatibility smoke test: spin a plain Debian container as a "machine", install a given version of
# Claude Code on it, then drive the real provisioning + login flow through the API until the machine
# reaches AWAITING_CODE (Claude Code launched, connected through the engine, and emitted an OAuth
# authorize URL the orchestrator scraped). Needs NO real Anthropic account — it stops where a human
# would paste a code. Run from an up compose bundle directory (has .env, keys/).
#
# It exercises BOTH login paths on the same machine:
#   round 1 — a fresh, never-onboarded machine (first-run wizard: theme -> login method -> OAuth), and
#   round 2 — an already-onboarded machine (main prompt; the orchestrator must run `/login` itself).
#
# Usage: machine-login-smoke.sh <install-spec>
#   install-spec = "installer"      -> curl https://claude.ai/install.sh | bash   (latest, unpinned)
#                  "npm:<version>"  -> npm i -g @anthropic-ai/claude-code@<version>
set -euo pipefail

INSTALL="${1:-installer}"
GW="http://localhost:$(grep -E '^NGINX_HTTP_PORT=' .env | cut -d= -f2 || echo 80)/ccproxy"
MACHINE="ccproxy-testmachine"
# Put the test machine on whatever network the running backend is on (robust to stack naming and to
# other stale ccproxy_* networks being present).
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
  for _ in $(seq 1 40); do
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
        *oauth*) echo "PASS $2: awaitingCode with URL ${url:0:56}..."
                 curl -s -X POST "$GW/login-request/$lrid/cancel" -H "Authorization: Bearer $OSEC" >/dev/null 2>&1 || true
                 return 0 ;;
        *) echo "FAIL $2: awaitingCode but no oauth URL"; return 1 ;;
      esac
    fi
    [ "$ls" = "failed" ] && { echo "FAIL $2: prepare failed:"; printf '%s' "$body" | jqget "['error']"; return 1; }
    sleep 3
  done
  echo "FAIL $2: never reached awaitingCode (last=$ls)"; return 1
}

echo "== register + provision machine =="
MID="$(curl -s -X POST "$GW/machine" -H "Authorization: Bearer $TSEC" -H 'Content-Type: application/json' \
        -d "{\"host\":\"$MACHINE\",\"label\":\"ci\"}" | jqget "['id']")"
wait_status "$MID" awaitingLogin || exit 1
echo "provisioned OK"

echo "== round 1: fresh machine (first-run wizard path) =="
expect_awaiting_code "$MID" "round1-fresh-wizard" || exit 1

echo "== round 2: simulate an already-onboarded machine, expect the /login path =="
curl -s -X POST "$GW/machine/$MID/reprovision" -H "Authorization: Bearer $TSEC" >/dev/null
wait_status "$MID" awaitingLogin || exit 1
# Mark onboarding complete so Claude Code lands on the main prompt (not the first-run wizard).
docker exec "$MACHINE" bash -lc 'python3 -c "import json,os;p=os.path.expanduser(\"~/.claude.json\");d=json.load(open(p)) if os.path.exists(p) and os.path.getsize(p) else {};d[\"hasCompletedOnboarding\"]=True;json.dump(d,open(p,\"w\"))"'
expect_awaiting_code "$MID" "round2-onboarded-login" || exit 1

echo "ALL PASS [$INSTALL]"
