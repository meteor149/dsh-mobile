$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$SourceDirectory = if ($env:UBUNTU_SOURCE_DIR) { $env:UBUNTU_SOURCE_DIR } else { Split-Path $ProjectRoot -Parent }
if ($env:BUILD_UBUNTU_LIBRARIES -eq 'true') {
    & (Join-Path $SourceDirectory 'android-ubuntu-runtime/runtime/build-runtime.ps1')
    & (Join-Path $SourceDirectory 'android-ubuntu-image/runtime/build-runtime.ps1')
}
& (Join-Path $ProjectRoot 'dsh-runtime/runtime/build-runtime.ps1')
& (Join-Path $PSScriptRoot 'proroot/fetch-proroot.ps1')
node (Join-Path $ProjectRoot 'tools/generate-runtime-manifest.mjs') (Join-Path $PSScriptRoot 'dist') --component proroot
if ($LASTEXITCODE -ne 0) { throw 'Runtime manifest generation failed' }
& (Join-Path $ProjectRoot 'gradlew.bat') ':app:prepareRuntimeAssets'
if ($LASTEXITCODE -ne 0) { throw 'Runtime Gradle staging failed' }
