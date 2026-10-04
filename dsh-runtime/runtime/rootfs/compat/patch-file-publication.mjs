import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const original = 'const linkFile = internals.linkFile ?? link;'
const replacement = 'const linkFile = internals.linkFile ?? publishNewFile;'
const importLine = 'import { publishNewFile } from "/opt/dsh-mobile/compat/file-publication.mjs";'

export function patchPublication(source) {
  if (source.includes(importLine) && source.includes(replacement) && !source.includes(original)) return source
  if (source.split(original).length !== 2 || source.includes(importLine) || source.includes(replacement)) {
    throw new Error('DSH file publication changed upstream; review the Android compatibility patch')
  }
  return `${importLine}\n${source.replace(original, replacement)}`
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const target = path.join(process.argv[2] ?? '/opt/dsh/node_modules/@deepseek-ai/dsh-fs-local', 'lib/index.js')
  const source = await readFile(target, 'utf8')
  await writeFile(target, patchPublication(source))
}
