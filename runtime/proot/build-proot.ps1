$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $ProjectRoot
try {
    bash './runtime/proot/build-proot.sh'
    if ($LASTEXITCODE -ne 0) { throw 'PRoot build failed' }
} finally {
    Pop-Location
}
