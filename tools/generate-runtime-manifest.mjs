import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { readFile, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'

const projectRoot = path.resolve(import.meta.dirname, '..')
const dist = path.resolve(process.argv[2] ?? path.join(projectRoot, 'runtime', 'dist'))
const versions = parseEnv(await readFile(path.join(projectRoot, 'runtime', 'versions.env'), 'utf8'))
const rootfsFile = 'dsh-ubuntu-arm64.tar.zst'
const nativeFiles = [
  ['libdsh_proot.so', 'libdsh_proot.so'],
  ['libdsh_proot_loader.so', 'libdsh_proot_loader.so'],
  ['libandroid-shmem.so', 'libandroid-shmem.so'],
  ['libdsh_talloc.so', 'libdsh_talloc.so'],
]

const rootfsPath = path.join(dist, rootfsFile)
const rootfsStat = await stat(rootfsPath)
const nativeLibraries = []
for (const [file, packagedName] of nativeFiles) {
  const artifactPath = path.join(dist, file)
  await stat(artifactPath)
  nativeLibraries.push({ file, packagedName, sha256: await sha256(artifactPath) })
}

const manifest = {
  schemaVersion: 1,
  available: true,
  runtimeVersion: required(versions, 'RUNTIME_VERSION'),
  abi: 'arm64-v8a',
  rootfs: {
    file: rootfsFile,
    sha256: await sha256(rootfsPath),
    compressedBytes: rootfsStat.size,
    minimumFreeBytes: Math.max(2_147_483_648, rootfsStat.size * 5),
  },
  nativeLibraries,
  entrypoint: {
    prootLibrary: 'libdsh_proot.so',
    loaderLibrary: 'libdsh_proot_loader.so',
    guestCommand: '/usr/local/bin/dsh-mobile-gateway',
  },
  sources: {
    ubuntuImage: required(versions, 'UBUNTU_IMAGE'),
    nodeVersion: required(versions, 'NODE_VERSION'),
    nodeDistributionSha256: required(versions, 'NODE_LINUX_ARM64_GZIP_SHA256'),
    dshVersion: required(versions, 'DSH_VERSION'),
    dshPackageIntegrity: required(versions, 'DSH_PACKAGE_INTEGRITY'),
    termuxProotVersion: required(versions, 'TERMUX_PROOT_VERSION'),
    termuxProotCommit: required(versions, 'TERMUX_PROOT_COMMIT'),
    termuxPackagesCommit: required(versions, 'TERMUX_PACKAGES_COMMIT'),
  },
}

await writeFile(path.join(dist, 'runtime-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`)
process.stdout.write(`runtime manifest: ${path.join(dist, 'runtime-manifest.json')}\n`)

async function sha256(file) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}

function parseEnv(text) {
  return Object.fromEntries(
    text.split(/\r?\n/u)
      .map(line => line.trim())
      .filter(line => line !== '' && !line.startsWith('#'))
      .map(line => {
        const separator = line.indexOf('=')
        if (separator <= 0) throw new Error(`Invalid versions.env line: ${line}`)
        return [line.slice(0, separator), line.slice(separator + 1)]
      }),
  )
}

function required(values, name) {
  const value = values[name]
  if (typeof value !== 'string' || value === '') throw new Error(`Missing ${name} in runtime/versions.env`)
  return value
}
