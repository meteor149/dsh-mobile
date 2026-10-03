import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { readFile, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
const root = path.resolve(import.meta.dirname, '..')
const dist = path.resolve(process.argv[2] ?? path.join(root, 'runtime/dist'))
const versions = Object.fromEntries((await readFile(path.join(root, 'runtime/versions.env'), 'utf8'))
  .split(/\r?\n/u).filter(line => line && !line.startsWith('#')).map(line => [line.slice(0,line.indexOf('=')),line.slice(line.indexOf('=')+1)]))
const file = 'dsh-runtime-arm64.tar.zst'
const hash = createHash('sha256')
for await (const chunk of createReadStream(path.join(dist,file))) hash.update(chunk)
await writeFile(path.join(dist,'dsh-manifest.json'), JSON.stringify({
  schemaVersion: 1, available: true, version: versions.DSH_PAYLOAD_VERSION, abi: 'arm64-v8a',
  archive: {file, sha256: hash.digest('hex'), compressedBytes: (await stat(path.join(dist,file))).size, minimumFreeBytes: 1073741824},
  nodeVersion: versions.NODE_VERSION, dshVersion: versions.DSH_VERSION,
},null,2)+'\n')
