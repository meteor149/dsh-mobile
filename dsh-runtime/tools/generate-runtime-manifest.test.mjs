import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'

test('DSH payload gets its own identity and checksum without Ubuntu artifacts', async () => {
  const dist = await mkdtemp(path.join(tmpdir(), 'dsh-payload-'))
  try {
    const content = Buffer.from('independently versioned application payload')
    await writeFile(path.join(dist, 'dsh-runtime-arm64.tar.zst'), content)
    execFileSync(process.execPath, [path.join(import.meta.dirname, 'generate-runtime-manifest.mjs'), dist])
    const manifest = JSON.parse(await readFile(path.join(dist, 'dsh-manifest.json'), 'utf8'))
    assert.equal(manifest.schemaVersion, 1)
    assert.equal(manifest.available, true)
    assert.equal(manifest.archive.compressedBytes, content.length)
    assert.equal(manifest.archive.sha256, createHash('sha256').update(content).digest('hex'))
    assert.ok(manifest.version.includes(manifest.dshVersion))
    assert.ok(manifest.version.includes(manifest.nodeVersion))
    assert.equal(manifest.rootfs, undefined)
    assert.equal(manifest.nativeLibraries, undefined)
  } finally {
    await rm(dist, { recursive: true, force: true })
  }
})
