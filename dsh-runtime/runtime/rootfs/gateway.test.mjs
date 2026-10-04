import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, test } from 'node:test'

const token = 'test-token-0123456789-abcdefghijklmnopqrstuvwxyz'
const temporary = await mkdtemp(path.join(os.tmpdir(), 'dsh-mobile-gateway-'))
const mockCli = path.join(temporary, 'mock-dsh.mjs')
await writeFile(mockCli, `
  import http from 'node:http'
  const server = http.createServer((request, response) => {
    if (request.url === '/?token=upstream-process-token') {
      response.writeHead(303, { location: './', 'set-cookie': 'dsh_backend=session; HttpOnly; Path=/' })
      response.end()
      return
    }
    if (request.headers.cookie !== 'dsh_backend=session') {
      response.writeHead(401)
      response.end('Backend authentication required')
      return
    }
    if (request.url === '/') {
      const body = '<!doctype html><html><head><meta name="viewport" content="width=1024"></head><body><div id="root"></div></body></html>'
      response.writeHead(200, {
        'content-type': 'text/html; charset=utf-8',
        'content-length': Buffer.byteLength(body),
        etag: 'mock-shell',
      })
      response.end(body)
      return
    }
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify({ path: request.url, cookie: request.headers.cookie ?? null, args: process.argv.slice(2) }))
  })
  server.on('upgrade', (request, socket) => {
    if (request.headers.cookie !== 'dsh_backend=session') {
      socket.end('HTTP/1.1 401 Unauthorized\\r\\n\\r\\n')
      return
    }
    socket.write('HTTP/1.1 101 Switching Protocols\\r\\nUpgrade: websocket\\r\\nConnection: Upgrade\\r\\n\\r\\n')
  })
  server.listen(0, '127.0.0.1', () => {
    console.log('dsh web: http://127.0.0.1:' + server.address().port + '/?token=upstream-process-token')
  })
  process.on('SIGTERM', () => server.close(() => process.exit(0)))
`)

const gateway = spawn(process.execPath, [path.join(import.meta.dirname, 'gateway.mjs')], {
  env: {
    ...process.env,
    DSH_MOBILE_TOKEN: token,
    DSH_NODE_BIN: process.execPath,
    DSH_CLI_PATH: mockCli,
    DSH_MOBILE_STDIN_FILE: mockCli,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let gatewayOutput = ''
const gatewayPort = await new Promise((resolve, reject) => {
  let output = ''
  const timeout = setTimeout(() => reject(new Error(`gateway readiness timeout: ${output}`)), 10_000)
  gateway.stdout.on('data', chunk => {
    output += chunk.toString()
    gatewayOutput += chunk.toString()
    const match = /dsh-mobile gateway: http:\/\/127\.0\.0\.1:(\d+)/.exec(output)
    if (match) {
      clearTimeout(timeout)
      resolve(Number(match[1]))
    }
  })
  gateway.once('error', reject)
  gateway.once('exit', code => reject(new Error(`gateway exited early: ${code}`)))
})

test('keeps backend launch credentials out of forwarded logs', () => {
  assert.ok(!gatewayOutput.includes('upstream-process-token'))
  assert.match(gatewayOutput, /token=<redacted>/u)
})

after(async () => {
  gateway.kill('SIGTERM')
  await new Promise(resolve => gateway.once('exit', resolve))
  await rm(temporary, { recursive: true, force: true })
})

test('requires a launch token and exchanges it for an HttpOnly cookie', async () => {
  const base = `http://127.0.0.1:${gatewayPort}`
  const denied = await fetch(base, { redirect: 'manual' })
  assert.equal(denied.status, 401)

  const handshake = await fetch(`${base}/?token=${token}`, { redirect: 'manual' })
  assert.equal(handshake.status, 302)
  assert.equal(handshake.headers.get('location'), '/')
  const cookie = handshake.headers.get('set-cookie')
  assert.match(cookie, /HttpOnly/u)
  assert.match(cookie, /SameSite=Strict/u)

  const shell = await fetch(`${base}/`, { headers: { cookie } })
  assert.equal(shell.status, 200)
  assert.equal(shell.headers.get('etag'), null)
  const html = await shell.text()
  assert.equal((html.match(/name="viewport"/gu) ?? []).length, 1)
  assert.match(html, /data-dsh-mobile-ui="webview"/u)
  assert.match(html, /\/__dsh_mobile\/ui\.css/u)
  assert.match(html, /\/__dsh_mobile\/ui\.js/u)

  const style = await fetch(`${base}/__dsh_mobile/ui.css`, { headers: { cookie } })
  assert.equal(style.status, 200)
  assert.match(style.headers.get('content-type'), /^text\/css/u)
  const styleText = await style.text()
  assert.match(styleText, /data-dsh-mobile-frame/u)
  assert.match(styleText, /data-dsh-mobile-settings/u)
  assert.doesNotMatch(styleText, /data-dsh-mobile-ui-menu/u)

  const script = await fetch(`${base}/__dsh_mobile/ui.js`, { headers: { cookie } })
  assert.equal(script.status, 200)
  assert.match(script.headers.get('content-type'), /^text\/javascript/u)
  const scriptText = await script.text()
  assert.match(scriptText, /data-dsh-mobile-role/u)
  assert.match(scriptText, /stampSettingsPage/u)
  assert.doesNotMatch(scriptText, /data-dsh-mobile-ui-menu/u)
  assert.match(scriptText, /\[role="treeitem"\]\[aria-selected\]/u)
  assert.match(scriptText, /new session\|新建会话/iu)
  assert.match(scriptText, /document\.addEventListener\('click', onSidebarClick, true\)/u)

  const proxied = await fetch(`${base}/api/probe`, { headers: { cookie } })
  assert.equal(proxied.status, 200)
  assert.deepEqual(await proxied.json(), {
    path: '/api/probe',
    cookie: 'dsh_backend=session',
    args: [
      'web',
      '--patch',
      fileURLToPath(new URL('./android.patch.yml', import.meta.url)),
      '--host',
      '127.0.0.1',
      '--port',
      '0',
      '--no-open',
    ],
  })
})

test('authenticates and tunnels a WebSocket upgrade', async () => {
  const response = await new Promise((resolve, reject) => {
    const socket = net.connect(gatewayPort, '127.0.0.1', () => {
      socket.write(
        `GET /events HTTP/1.1\r\nHost: 127.0.0.1:${gatewayPort}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nCookie: dsh_mobile_session=${token}\r\n\r\n`,
      )
    })
    socket.once('data', chunk => {
      resolve(chunk.toString())
      socket.destroy()
    })
    socket.once('error', reject)
  })
  assert.match(response, /101 Switching Protocols/u)
})
