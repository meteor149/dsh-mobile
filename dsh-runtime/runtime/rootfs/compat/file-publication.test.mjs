import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { publicationHelper } from './file-publication.mjs'

test('accepts an explicitly supplied publication helper', () => {
  const helper = '/opt/android-ubuntu-runtime/file-publication.py'
  assert.equal(publicationHelper({ UBUNTU_FILE_PUBLICATION_HELPER: helper }), helper)
})

test('uses the app payload helper by default', () => {
  assert.equal(publicationHelper({}), fileURLToPath(new URL('./file-publication.py', import.meta.url)))
})
