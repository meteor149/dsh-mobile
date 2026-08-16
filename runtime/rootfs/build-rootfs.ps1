$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$VersionsFile = Join-Path $ProjectRoot 'runtime\versions.env'
$Versions = @{}
Get-Content $VersionsFile | ForEach-Object {
    $Line = $_.Trim()
    if ($Line -and -not $Line.StartsWith('#')) {
        $Parts = $Line.Split('=', 2)
        $Versions[$Parts[0]] = $Parts[1]
    }
}

$Dist = Join-Path $ProjectRoot 'runtime\dist'
New-Item -ItemType Directory -Path $Dist -Force | Out-Null
$NodeDist = Join-Path $ProjectRoot 'runtime\.work\rootfs\node-dist'
$NodeArchive = Join-Path $NodeDist 'node.tar.gz'
$NodeArchivePart = "$NodeArchive.part"
New-Item -ItemType Directory -Path $NodeDist -Force | Out-Null
$ArchiveValid = (Test-Path $NodeArchive) -and ((Get-FileHash -Algorithm SHA256 $NodeArchive).Hash -eq $Versions.NODE_LINUX_ARM64_GZIP_SHA256)
if (-not $ArchiveValid) {
    Remove-Item -LiteralPath $NodeArchivePart -Force -ErrorAction SilentlyContinue
    $NodeUrl = "https://nodejs.org/dist/v$($Versions.NODE_VERSION)/node-v$($Versions.NODE_VERSION)-linux-arm64.tar.gz"
    Invoke-WebRequest -Uri $NodeUrl -OutFile $NodeArchivePart
    $ActualHash = (Get-FileHash -Algorithm SHA256 $NodeArchivePart).Hash
    if ($ActualHash -ne $Versions.NODE_LINUX_ARM64_GZIP_SHA256) {
        throw "Node archive checksum mismatch: expected=$($Versions.NODE_LINUX_ARM64_GZIP_SHA256) actual=$ActualHash"
    }
    Move-Item -LiteralPath $NodeArchivePart -Destination $NodeArchive -Force
}
$Arguments = @('buildx', 'build') + @(
    '--platform', 'linux/arm64',
    '--file', (Join-Path $PSScriptRoot 'Containerfile'),
    '--build-context', "node-dist=$NodeDist",
    '--build-arg', "UBUNTU_IMAGE=$($Versions.UBUNTU_IMAGE)",
    '--build-arg', "NODE_VERSION=$($Versions.NODE_VERSION)",
    '--build-arg', "NODE_LINUX_ARM64_GZIP_SHA256=$($Versions.NODE_LINUX_ARM64_GZIP_SHA256)",
    '--build-arg', "DSH_VERSION=$($Versions.DSH_VERSION)",
    '--build-arg', "DSH_PACKAGE_INTEGRITY=$($Versions.DSH_PACKAGE_INTEGRITY)",
    '--target', 'artifact',
    '--output', "type=local,dest=$Dist",
    $PSScriptRoot
)
docker @Arguments
if ($LASTEXITCODE -ne 0) { throw 'rootfs image build failed' }
Write-Host "rootfs artifact: $(Join-Path $Dist 'dsh-ubuntu-arm64.tar.zst')"
