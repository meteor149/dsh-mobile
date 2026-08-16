import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { after, test } from 'node:test'

const token = 'test-token-0123456789-abcdefghijklmnopqrstuvwxyz'
const temporary = await mkdtemp(path.join(os.tmpdir(), 'dsh-mobile-gateway-'))
const mockCli = path.join(temporary, 'mock-dsh.mjs')
await writeFile(mockCli, `
  import http from 'node:http'
  const server = http.createServer((request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify({ path: request.url, cookie: request.headers.cookie ?? null }))
  })
  server.on('upgrade', (_request, socket) => {
    socket.write('HTTP/1.1 101 Switching Protocols\\r\\nUpgrade: websocket\\r\\nConnection: Upgrade\\r\\n\\r\\n')
  })
  server.listen(0, '127.0.0.1', () => {
    console.log('dsh web: http://127.0.0.1:' + server.address().port)
  })
  process.on('SIGTERM', () => server.close(() => process.exit(0)))
`)

const gateway = spawn(process.execPath, [path.join(import.meta.dirname, 'gateway.mjs')], {
  env: {
    ...process.env,
    DSH_MOBILE_TOKEN: token,
    DSH_NODE_BIN: process.execPath,
    DSH_CLI_PATH: mockCli,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
})
const gatewayPort = await new Promise((resolve, reject) => {
  let output = ''
  const timeout = setTimeout(() => reject(new Error(`gateway readiness timeout: ${output}`)), 10_000)
  gateway.stdout.on('data', chunk => {
    output += chunk.toString()
    const match = /dsh-mobile gateway: http:\/\/127\.0\.0\.1:(\d+)/.exec(output)
    if (match) {
      clearTimeout(timeout)
      resolve(Number(match[1]))
    }
  })
  gateway.once('error', reject)
  gateway.once('exit', code => reject(new Error(`gateway exited early: ${code}`)))
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

  const proxied = await fetch(`${base}/api/probe`, { headers: { cookie } })
  assert.equal(proxied.status, 200)
  assert.deepEqual(await proxied.json(), { path: '/api/probe', cookie: null })
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
