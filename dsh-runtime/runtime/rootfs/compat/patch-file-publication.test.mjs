import assert from 'node:assert/strict'
import test from 'node:test'
import { patchPublication } from './patch-file-publication.mjs'

test('patches only the guarded new-file publication hook and is idempotent', () => {
  const source = 'const linkFile = internals.linkFile ?? link;\nawait rename(tempPath, absolutePath);'
  const patched = patchPublication(source)
  assert.match(patched, /internals\.linkFile \?\? publishNewFile/)
  assert.ok(patched.endsWith('await rename(tempPath, absolutePath);'))
  assert.equal(patchPublication(patched), patched)
})

test('rejects missing or ambiguous upstream publication hooks', () => {
  assert.throws(() => patchPublication('await link(source, target);'), /changed upstream/)
  assert.throws(() => patchPublication('const linkFile = internals.linkFile ?? link;'.repeat(2)), /changed upstream/)
})
