param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$ownedContainer = $null
$names = @('POSTGRES_PASSWORD', 'JWORKFLOW_JDBC_URL', 'JWORKFLOW_JDBC_USERNAME', 'JWORKFLOW_JDBC_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N')
    $env:JWORKFLOW_JDBC_USERNAME = 'jworkflow_demo'
    $env:JWORKFLOW_JDBC_PASSWORD = $env:POSTGRES_PASSWORD
    $ownedContainer = & docker run -d --rm --label org.jworkflow.pg13-demo=true -p '127.0.0.1::5432' -e POSTGRES_PASSWORD -e POSTGRES_USER=jworkflow_demo -e POSTGRES_DB=jworkflow_demo postgres@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL failed to start' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec $ownedContainer pg_isready -U jworkflow_demo -d jworkflow_demo *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'PostgreSQL readiness timed out' }
    $binding = & docker port $ownedContainer 5432/tcp
    $port = ($binding -split ':')[-1]
    & docker exec $ownedContainer psql -X -v ON_ERROR_STOP=1 -U jworkflow_demo -d jworkflow_demo -c 'CREATE SCHEMA jworkflow AUTHORIZATION jworkflow_demo;'
    if ($LASTEXITCODE -ne 0) { throw 'Schema creation failed' }
    $env:JWORKFLOW_JDBC_URL = "jdbc:postgresql://127.0.0.1:$port/jworkflow_demo?currentSchema=jworkflow&connectTimeout=10&socketTimeout=30&options=-c%20statement_timeout=10000%20-c%20lock_timeout=5000"
    & "$PSScriptRoot/run-postgresql.ps1" -Phase init -Build:(!$SkipBuild)
    foreach ($phase in @('init', 'start', 'status', 'resume', 'status', 'publish', 'publish')) {
        & "$PSScriptRoot/run-postgresql.ps1" -Phase $phase
    }
    & docker exec $ownedContainer psql -X -v ON_ERROR_STOP=1 -U jworkflow_demo -d jworkflow_demo -c 'SELECT version(); SELECT status,count(*) FROM jworkflow.workflow_instance GROUP BY status; SELECT status_value,count(*) FROM jworkflow.workflow_inbox GROUP BY status_value; SELECT status_value,count(*) FROM jworkflow.workflow_outbox GROUP BY status_value; SELECT id,updated_at,floor(updated_at) AS epoch_second,(updated_at-floor(updated_at))*1000000000 AS nanos,to_timestamp(updated_at::double precision) AT TIME ZONE ''UTC'' AS approximate_utc FROM jworkflow.workflow_instance ORDER BY updated_at,id LIMIT 20; SELECT id,COALESCE(next_attempt_at,due_at) AS eligible_at FROM jworkflow.workflow_timer WHERE status_value IN (''PENDING'',''RETRY_SCHEDULED'') AND COALESCE(next_attempt_at,due_at) <= extract(epoch FROM statement_timestamp())::numeric(30,9) ORDER BY COALESCE(next_attempt_at,due_at),created_at,id LIMIT 20;'
    if ($LASTEXITCODE -ne 0) { throw 'Observation query failed' }
} finally {
    try {
        if ($ownedContainer) {
            & docker rm -f $ownedContainer | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Disposable container cleanup failed' }
            Write-Output "Removed disposable demo container: $ownedContainer"
        }
    } finally {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    }
}
