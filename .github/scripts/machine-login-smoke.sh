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

# Regression guard: provisioning now writes exactly ONE file — the login user's ~/.claude/settings.json
# — whose `env` block points Claude Code at the engine proxy (+ NODE_EXTRA_CA_CERTS for the MITM CA
# dropped alongside it). settings.json's env overrides shell/system env for every Claude this user
# starts, so ONLY Claude's traffic is proxied. It must NOT touch system-wide env (/etc/profile.d,
# /etc/environment) — those would proxy the whole machine and are exactly what this design removes.
echo "== verify settings.json carries the engine proxy; no system-wide env written =="
docker exec "$MACHINE" bash -lc 'cat "$HOME/.claude/settings.json"' | python3 -c "
import sys,json
env=json.load(sys.stdin).get('env',{})
p=env.get('HTTPS_PROXY','')
assert p.startswith('http') and '@' in p, 'HTTPS_PROXY missing/unauthed: %r'%p
assert env.get('HTTP_PROXY')==p, 'HTTP_PROXY != HTTPS_PROXY'
assert env.get('NODE_EXTRA_CA_CERTS'), 'NODE_EXTRA_CA_CERTS missing'
print('settings.json env OK ('+p+')')
" || { echo "FAIL: settings.json env invalid"; exit 1; }
docker exec "$MACHINE" bash -lc 'test -f "$HOME/.claude/ccproxy-ca.crt"' ||
  { echo "FAIL: MITM CA not dropped next to settings.json"; exit 1; }
docker exec "$MACHINE" test ! -e /etc/profile.d/ccproxy-proxy.sh ||
  { echo "FAIL: /etc/profile.d/ccproxy-proxy.sh must NOT exist under the settings.json design"; exit 1; }

echo "== round 1: fresh machine (first-run wizard path) =="
expect_awaiting_code "$MID" "round1-fresh-wizard" || exit 1

echo "== round 2: simulate an already-onboarded machine, expect the /login path =="
curl -s -X POST "$GW/machine/$MID/reprovision" -H "Authorization: Bearer $TSEC" >/dev/null
wait_status "$MID" awaitingLogin || exit 1
# Mark onboarding complete so Claude Code lands on the main prompt (not the first-run wizard).
docker exec "$MACHINE" bash -lc 'python3 -c "import json,os;p=os.path.expanduser(\"~/.claude.json\");d=json.load(open(p)) if os.path.exists(p) and os.path.getsize(p) else {};d[\"hasCompletedOnboarding\"]=True;json.dump(d,open(p,\"w\"))"'
expect_awaiting_code "$MID" "round2-onboarded-login" || exit 1

echo "== round 2b: a machine pointed at a third-party gateway (newapi env) must STILL log in against official =="
# Before a machine is switched to ccproxy it may be pointed at a third-party Anthropic gateway (e.g.
# newapi) via ANTHROPIC_BASE_URL + ANTHROPIC_AUTH_TOKEN in a login-shell profile. If the login Claude
# inherited that, it would run in API-key mode / hit the gateway and NEVER produce an OAuth URL, so
# the engine could not capture a real token. The orchestrator must launch its login Claude forced
# onto the official endpoint regardless. Reproduce that env and require login to still reach awaitingCode.
docker exec "$MACHINE" bash -c 'printf "export ANTHROPIC_BASE_URL=https://newapi.example.invalid\nexport ANTHROPIC_AUTH_TOKEN=sk-newapi-fake-token\n" > /etc/profile.d/zz-newapi.sh'
SEEN="$(docker exec "$MACHINE" bash -lc 'printf %s "$ANTHROPIC_BASE_URL"')"
[ "$SEEN" = "https://newapi.example.invalid" ] || { echo "FAIL: gateway env not visible in login shell (got '$SEEN')"; exit 1; }
curl -s -X POST "$GW/machine/$MID/reprovision" -H "Authorization: Bearer $TSEC" >/dev/null
wait_status "$MID" awaitingLogin || exit 1
expect_awaiting_code "$MID" "round2b-gateway-env-still-official" || exit 1
docker exec "$MACHINE" rm -f /etc/profile.d/zz-newapi.sh   # keep later rounds unaffected

echo "== round 2c: a switched-to-official persistent Claude must survive a concurrent login =="
# The real steady state after the newapi->official switch: provisioning only ever wrote settings.json,
# then the switch flips ANTHROPIC_BASE_URL in that same file to official and (re)starts a long-lived
# "student" Claude pointed at the official endpoint through the engine. Triggering a fresh login runs
# in ITS OWN tmux session and must not tear down the already-running student. Reproduce that here.
curl -s -X POST "$GW/machine/$MID/reprovision" -H "Authorization: Bearer $TSEC" >/dev/null
wait_status "$MID" awaitingLogin || exit 1
docker exec "$MACHINE" bash -lc 'python3 - <<PY
import json,os
p=os.path.expanduser("~/.claude/settings.json")
d=json.load(open(p))
d["env"]["ANTHROPIC_BASE_URL"]="https://api.anthropic.com"
json.dump(d,open(p,"w"))
PY
tmux new-session -d -s student "PATH=\$HOME/.local/bin:\$PATH claude || true; exec sleep infinity"'
sleep 3
docker exec "$MACHINE" tmux has-session -t student 2>/dev/null ||
  { echo "FAIL: persistent student Claude did not start"; exit 1; }
expect_awaiting_code "$MID" "round2c-persistent-official-claude-survives" || exit 1
docker exec "$MACHINE" tmux has-session -t student 2>/dev/null ||
  { echo "FAIL: the login flow killed the running student Claude session"; exit 1; }
echo "PASS round2c: persistent Claude survived the login"
docker exec "$MACHINE" tmux kill-session -t student 2>/dev/null || true

echo "== round 3: engine restart self-heal (lazy session fetch, NO reprovision) =="
# The engine keeps sessions in memory; restarting wipes them. Without on-demand refetch a machine
# would then stall at 'Checking connectivity' until reprovisioned. Restart the engine and DO NOT
# reprovision — a fresh login must still reach awaitingCode because the engine rebuilds the session
# from the backend on the first connection.
docker compose restart proxy-engine >/dev/null 2>&1
for _ in $(seq 1 20); do
  h="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q proxy-engine)" 2>/dev/null || true)"
  [ "$h" = "healthy" ] && break
  sleep 2
done
wait_status "$MID" awaitingLogin || exit 1
expect_awaiting_code "$MID" "round3-engine-restart-selfheal" || exit 1

echo "== round 4: token-persistence round-trip inside the engine container =="
# Captured tokens are persisted to disk and reloaded on restart, so a logged-in machine survives an
# engine restart without re-login. A real login needs an account, so exercise the persist/reload
# directly against the running engine module (with its real SESSION_DIR + mounted volume).
POUT="$(docker compose exec -T -w /app proxy-engine python3 - <<'PY'
import os, ccproxy_engine as e
assert e.SESSION_DIR, "CCPROXY_SESSION_DIR not set in engine"
s = e.REGISTRY.put("smoketest", "pw", "http://egress-proxy:7890")
s.real_access, s.real_refresh = "RA", "RR"
s.fake_access, s.fake_refresh, s.expires_at = "FA", "FR", 999
e.persist_session("smoketest", s)
e.REGISTRY._by_user.clear()          # simulate a restart wiping memory
e.load_all_persisted()               # reload from disk
s2 = e.REGISTRY.get("smoketest")
ok = bool(s2 and s2.real_refresh == "RR" and s2.fake_access == "FA" and s2.expires_at == 999)
os.remove(os.path.join(e.SESSION_DIR, "smoketest.json"))
print("PERSIST_OK" if ok else "PERSIST_FAIL")
PY
)"
case "$POUT" in
  *PERSIST_OK*) echo "PASS round4-token-persistence" ;;
  *) echo "FAIL round4-token-persistence: $POUT"; exit 1 ;;
esac

echo "ALL PASS [$INSTALL]"
