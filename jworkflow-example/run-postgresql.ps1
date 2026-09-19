param(
    [ValidateSet('init', 'start', 'resume', 'publish', 'status')]
    [string]$Phase = 'status',
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if ($Build) {
        $ErrorActionPreference = 'Continue'
        & mvn -B -ntp package org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies '-DincludeScope=runtime' '-DexcludeArtifactIds=sqlite-jdbc' '-DoutputDirectory=target/dependency'
        $buildExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($buildExit -ne 0) { throw 'Example build failed' }
    }
    $dependencyDirectory = Join-Path $projectRoot 'jworkflow-jdbc/target/dependency'
    if (!(Test-Path -LiteralPath $dependencyDirectory)) { throw 'Run with -Build first' }
    $jars = @(Get-ChildItem -LiteralPath $dependencyDirectory -Filter '*.jar' |
        Where-Object { $_.Name -notlike 'sqlite-jdbc-*' } | ForEach-Object { $_.FullName })
    $classPath = (@((Join-Path $projectRoot 'jworkflow-example/target/classes'),
        (Join-Path $projectRoot 'jworkflow-jdbc/target/classes')) + $jars) -join [IO.Path]::PathSeparator
    $ErrorActionPreference = 'Continue'
    & java -cp $classPath org.jworkflow.example.PostgresqlOrderExample $Phase
    $exampleExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exampleExit -ne 0) { throw "Example phase failed: $Phase" }
} finally { Pop-Location }
