$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'rootfs/build-rootfs.ps1')
node (Join-Path $ProjectRoot 'tools/generate-runtime-manifest.mjs') (Join-Path $PSScriptRoot 'dist')
if ($LASTEXITCODE -ne 0) { throw 'Manifest generation failed' }
