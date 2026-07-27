# Deploy

Self-contained bundle: stock official images (nginx / JRE / postgres / python) with this project's
build artifacts and the proxy-engine code **bind-mounted** in — no custom images to build.

```
docker-compose.yml     five services (nginx, backend, proxy-engine, egress-proxy, postgres)
nginx.conf             domain-independent gateway (SPA + /ccproxy -> backend)
gen-env.sh             generates .env (secrets) + app_data/ + keys/ (operator SSH keypair + MITM CA)
init/                  postgres first-init SQL (creates the "ccproxy" schema)
CREATE.sql             the DB schema this release expects (for ops; hand-write migrations from diffs)
backend/backend.jar    the backend (CI fills this in the shipped bundle)
frontend/dist/         built test SPA (CI fills this in the shipped bundle)
proxy-engine/          the MITM engine + egress proxy (stdlib Python; run on a stock python image)
keys/                  not shipped; gen-env.sh creates it (operator SSH keypair + ca.crt/ca.key)
app_data/              not shipped; gen-env.sh creates it; all persistent state lives here
```

## Three steps

```bash
bash gen-env.sh          # once: writes .env (random secrets), app_data/, and keys/ (SSH + CA)
docker compose up -d     # pulls stock images, bind-mounts the jar + engine code
docker compose ps        # wait for every service 'healthy'; nginx listens on :80
```

Open the gateway (port 80): the **test console** SPA. Log in as **Super-admin** with the
`SUPERADMIN_PASSWORD` from `.env`. From there: add Anthropic accounts to the pool, mint a tenant
secret and a login-operator secret. A tenant then registers machines (after injecting the SSH public
key from `/ccproxy/provisioning/ssh-pubkey`) and triggers logins; a login-operator completes each
login from the queue.

## Networking

The **backend** SSHes into machines (provisioning + driving `/login`), so run the bundle on a host
that can reach the machine IPs. The **proxy-engine** listens on `:3128` (a machine's `HTTPS_PROXY`)
and `:9000` (backend control); the **egress-proxy** on `:7890` is the default upstream every account
points at (it just uses the host's own network). For real per-account egress separation, set an
account's `proxy` to a distinct upstream — the schema already supports it.

## Environment variables (`.env`)

`gen-env.sh` writes these; leave them as-is:

| Variable | Purpose |
| --- | --- |
| `POSTGRES_USER` / `POSTGRES_DB` / `POSTGRES_PASSWORD` | DB credentials |
| `JWT_SECRET` | signs/verifies session tokens |
| `SUPERADMIN_PASSWORD` | the super-admin login password |
| `ENGINE_SECRET` | shared secret for the backend ↔ proxy-engine control channel |
| `NGINX_HTTP_PORT` | host port the gateway listens on (default 80) |

## Domain-independent

The backend derives its own public URL from `X-Forwarded-Proto`/`X-Forwarded-Host`. Put your own
TLS-terminating reverse proxy in front of port 80 and forward those headers.
