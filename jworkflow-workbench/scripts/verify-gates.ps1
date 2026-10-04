$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    foreach ($counter in @('line', 'branch')) {
        $output = & mvn -o -B -ntp "-Dcoverage.$counter.minimum=1.00" jacoco:check@coverage-gate 2>&1
        if ($LASTEXITCODE -eq 0) { throw "$counter gate did not reject the controlled shortfall" }
        $text = $output -join "`n"
        if ($text -notmatch "$counter.*covered ratio") { throw "$counter failed for a reason other than coverage: $text" }
        Write-Output "PASS: independent $counter coverage shortfall is rejected"
    }
} finally { Pop-Location }
