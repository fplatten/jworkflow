#!/usr/bin/env bash
# Runs one phase of the PostgreSQL order example in its own JVM. POSIX counterpart of run-postgresql.ps1.
# Usage: run-postgresql.sh [--build] [init|start|resume|publish|status]
# Requires JWORKFLOW_JDBC_URL, JWORKFLOW_JDBC_USERNAME and JWORKFLOW_JDBC_PASSWORD in the environment.
set -euo pipefail

usage() {
    echo "usage: $0 [--build] [init|start|resume|publish|status]" >&2
    exit 2
}

build=false
phase=status
for arg in "$@"; do
    case "$arg" in
        --build) build=true ;;
        init | start | resume | publish | status) phase="$arg" ;;
        *) usage ;;
    esac
done

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if [ "$build" = true ]; then
    mvn -B -ntp package org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies \
        -DincludeScope=runtime -DexcludeArtifactIds=sqlite-jdbc -DoutputDirectory=target/dependency
fi

dependencies="$root/jworkflow-jdbc/target/dependency"
if [ ! -d "$dependencies" ]; then
    echo "Run with --build first" >&2
    exit 1
fi

# Exclude any stale SQLite driver so the example proves it runs without SQLite.
classpath="$root/jworkflow-example/target/classes:$root/jworkflow-jdbc/target/classes"
for jar in "$dependencies"/*.jar; do
    case "$(basename "$jar")" in
        sqlite-jdbc-*) ;;
        *) classpath="$classpath:$jar" ;;
    esac
done

exec java -cp "$classpath" org.jworkflow.example.PostgresqlOrderExample "$phase"
