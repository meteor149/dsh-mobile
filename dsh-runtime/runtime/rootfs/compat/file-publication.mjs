import { execFile } from 'node:child_process'
import { link } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const execute = promisify(execFile)
// This helper belongs to DSH's private staging-file protocol. It does not
// replace general Ubuntu hard-link semantics.
export function publicationHelper(environment = process.env) {
  return environment.UBUNTU_FILE_PUBLICATION_HELPER
    ?? fileURLToPath(new URL('./file-publication.py', import.meta.url))
}

/** DSH's staging file is private and discarded after publication, so moving it is safe. */
export async function publishNewFile(source, destination) {
  if (!['proot', 'proroot'].includes(process.env.DSH_MOBILE_RUNTIME_MODE)) return link(source, destination)
  try {
    await execute('/usr/bin/python3', ['-I', publicationHelper(), source, destination], { timeout: 15000, maxBuffer: 4096 })
  } catch (error) {
    let details
    try { details = JSON.parse(error.stderr) } catch { throw error }
    const failure = new Error(details.message, { cause: error })
    failure.code = details.code
    failure.syscall = 'renameat2'
    throw failure
  }
}
