#!/usr/bin/env bash
#
# Bring up the integration-test dependencies locally / in CI: a Postgres with the "ccproxy"
# schema. Mirrors what the tests expect (a real DB — CCProxy's tests are integration tests).
#
set -euo pipefail

docker rm -f ccproxy-postgres >/dev/null 2>&1 || true

docker run -d --name ccproxy-postgres \
    -e POSTGRES_USER=postgres \
    -e POSTGRES_PASSWORD=postgres \
    -e POSTGRES_DB=postgres \
    -p 5432:5432 \
    postgres:16.2 >/dev/null

echo "waiting for postgres..."
for _ in $(seq 1 30); do
    if docker exec ccproxy-postgres pg_isready -U postgres >/dev/null 2>&1; then break; fi
    sleep 1
done
docker exec ccproxy-postgres psql -U postgres -d postgres \
    -c 'CREATE SCHEMA IF NOT EXISTS ccproxy;'
echo "postgres up with 'ccproxy' schema on localhost:5432"
