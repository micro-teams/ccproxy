# CLAUDE.md

Guidance for agents (and humans) working in this repository.

## Commits

- Author every commit as the **project owner**, using their name and email — never as an agent/bot.
- Do **not** add a `Co-Authored-By` (or any bot) trailer to commit messages.

## What this is

CCProxy gives a remote machine a billable, normal-plan **Claude Code** while the real OAuth
credentials never leave the proxy. See [`README.md`](README.md) for the model and roles, and
[`CCProxy-API.yml`](CCProxy-API.yml) — the single API contract that both the backend
(`app.microteams.ccproxy.api.*Api`) and the frontend client are generated from. **Change the API by
editing the yaml first**, then `./mvnw install`.

## Versioning

The repo-root `VERSION` file is the single source of truth. **Never bump versions by hand** — run
`scripts/version.sh <X.Y.Z>`, which propagates it into every artifact that must ship it
(`backend/pom.xml`, `CCProxy-API.yml`'s `info.version` = `X.Y`, `frontend/package.json`, and the
lockfile). `scripts/version.sh` with no argument prints the current version and verifies all files
agree.

## The one rule that must never break

One machine = one independent Claude Code login, exactly like a person on their own computer. Real
tokens are per-machine and never shared/deduplicated across machines. Never run `claude -p` /
`--console` — it uses a different quota. The account pool is CCProxy-internal; **tenants never see
it**.

## Layout

- `backend/` — Kotlin/Spring. Borrowed authz stays under `org.rucca.cheese`; everything else is
  `app.microteams.ccproxy`. Domains: `authz`, `tenant`, `loginoperator`, `account`, `machine`,
  `loginrequest`, `usage`, `provisioning`, `superadmin`, `ping`.
- `proxy-engine/` — the MITM data plane + default egress proxy (stdlib Python).
- `deploy/` — the docker-compose bundle (see `deploy/README.md`).
