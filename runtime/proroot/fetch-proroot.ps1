$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $ProjectRoot
try {
    bash './runtime/proroot/fetch-proroot.sh'
    if ($LASTEXITCODE -ne 0) { throw 'proroot artifact fetch failed' }
} finally {
    Pop-Location
}
