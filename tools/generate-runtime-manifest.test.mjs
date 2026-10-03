import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'

const generator = path.join(import.meta.dirname, 'generate-runtime-manifest.mjs')

test('engine manifest needs neither image nor restricted proroot binaries', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'ubuntu-engine-'))
  try {
    for (const file of ['libdsh_proot.so', 'libdsh_proot_loader.so', 'libandroid-shmem.so', 'libdsh_talloc.so']) {
      await writeFile(path.join(dir, file), `fixture ${file}`)
    }
    execFileSync(process.execPath, [generator, dir, '--component', 'engine'])
    const manifest = JSON.parse(await readFile(path.join(dir, 'runtime-manifest.json'), 'utf8'))
    assert.equal(manifest.rootfs, undefined)
    assert.equal(manifest.nativeLibraries.length, 4)
    assert.ok(manifest.nativeLibraries.every(item => !item.file.startsWith('libproroot')))
    assert.ok(manifest.nativeLibraries.every(item => /^[a-f0-9]{64}$/.test(item.sha256)))
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('image manifest needs no native runtime files', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'ubuntu-image-'))
  try {
    await writeFile(path.join(dir, 'ubuntu-arm64.tar.zst'), 'fixture rootfs')
    execFileSync(process.execPath, [generator, dir, '--component', 'image'])
    const manifest = JSON.parse(await readFile(path.join(dir, 'runtime-manifest.json'), 'utf8'))
    assert.deepEqual(manifest.nativeLibraries, [])
    assert.equal(manifest.rootfs.compressedBytes, 14)
    assert.match(manifest.rootfs.sha256, /^[a-f0-9]{64}$/)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})
