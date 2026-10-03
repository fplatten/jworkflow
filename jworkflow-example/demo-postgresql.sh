#!/usr/bin/env bash
# Runs the PostgreSQL restart demonstration against a disposable container, each phase in a separate JVM.
# POSIX counterpart of demo-postgresql.ps1. Usage: demo-postgresql.sh [--skip-build]
set -euo pipefail

skip_build=false
case "${1:-}" in
    "") ;;
    --skip-build) skip_build=true ;;
    *) echo "usage: $0 [--skip-build]" >&2; exit 2 ;;
esac

here="$(cd "$(dirname "$0")" && pwd)"
image=postgres@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675
container=""

# Remove only the container this script started, however the script ends.
cleanup() {
    if [ -n "$container" ]; then
        docker rm -f "$container" >/dev/null
        echo "Removed disposable demo container: $container"
    fi
}
trap cleanup EXIT

# The generated password lives only in this process's environment and is never printed.
POSTGRES_PASSWORD="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
export POSTGRES_PASSWORD
export JWORKFLOW_JDBC_USERNAME=jworkflow_demo
export JWORKFLOW_JDBC_PASSWORD="$POSTGRES_PASSWORD"

container="$(docker run -d --rm --label org.jworkflow.pg13-demo=true -p 127.0.0.1::5432 \
    -e POSTGRES_PASSWORD -e POSTGRES_USER=jworkflow_demo -e POSTGRES_DB=jworkflow_demo "$image")"

ready=false
for _ in $(seq 1 60); do
    if docker exec "$container" pg_isready -U jworkflow_demo -d jworkflow_demo >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [ "$ready" != true ]; then
    echo "PostgreSQL readiness timed out" >&2
    exit 1
fi

port="$(docker port "$container" 5432/tcp | head -n 1 | awk -F: '{print $NF}')"
docker exec "$container" psql -X -v ON_ERROR_STOP=1 -U jworkflow_demo -d jworkflow_demo \
    -c 'CREATE SCHEMA jworkflow AUTHORIZATION jworkflow_demo;'
export JWORKFLOW_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/jworkflow_demo?currentSchema=jworkflow&connectTimeout=10&socketTimeout=30&options=-c%20statement_timeout=10000%20-c%20lock_timeout=5000"

if [ "$skip_build" = true ]; then
    "$here/run-postgresql.sh" init
else
    "$here/run-postgresql.sh" --build init
fi
for phase in init start status resume status publish publish; do
    "$here/run-postgresql.sh" "$phase"
done

docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U jworkflow_demo -d jworkflow_demo <<'SQL'
SELECT version();
SELECT status, count(*) FROM jworkflow.workflow_instance GROUP BY status;
SELECT status_value, count(*) FROM jworkflow.workflow_inbox GROUP BY status_value;
SELECT status_value, count(*) FROM jworkflow.workflow_outbox GROUP BY status_value;
SELECT id, updated_at, floor(updated_at) AS epoch_second,
       (updated_at - floor(updated_at)) * 1000000000 AS nanos,
       to_timestamp(updated_at::double precision) AT TIME ZONE 'UTC' AS approximate_utc
FROM jworkflow.workflow_instance ORDER BY updated_at, id LIMIT 20;
SELECT id, COALESCE(next_attempt_at, due_at) AS eligible_at
FROM jworkflow.workflow_timer
WHERE status_value IN ('PENDING', 'RETRY_SCHEDULED')
  AND COALESCE(next_attempt_at, due_at) <= extract(epoch FROM statement_timestamp())::numeric(30,9)
ORDER BY COALESCE(next_attempt_at, due_at), created_at, id LIMIT 20;
SQL
