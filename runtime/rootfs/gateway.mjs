import { spawn } from 'node:child_process'
import { timingSafeEqual } from 'node:crypto'
import http from 'node:http'
import net from 'node:net'
import { createInterface } from 'node:readline'

const LOOPBACK = '127.0.0.1'
const COOKIE_NAME = 'dsh_mobile_session'
const token = process.env.DSH_MOBILE_TOKEN
const configuredNodeBinary = process.env.DSH_NODE_BIN
const dshCliPath = process.env.DSH_CLI_PATH ?? '/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js'
const externalBackendPort = parsePort(process.env.DSH_MOBILE_BACKEND_PORT)

if (typeof token !== 'string' || token.length < 32) {
  throw new Error('DSH_MOBILE_TOKEN must contain at least 32 characters')
}

const { DSH_MOBILE_TOKEN: _discardedToken, ...backendEnvironment } = process.env
const backendCommand = configuredNodeBinary ?? '/usr/local/bin/dsh'
const backendArguments = configuredNodeBinary
  ? [dshCliPath, 'web', '--host', LOOPBACK, '--port', '0']
  : ['web', '--host', LOOPBACK, '--port', '0']
const backend = externalBackendPort === undefined
  ? spawn(
      backendCommand,
      backendArguments,
      {
        env: backendEnvironment,
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    )
  : undefined

let backendPort = externalBackendPort
let backendReadyResolve
let backendReadyReject
const backendReady = new Promise((resolve, reject) => {
  backendReadyResolve = resolve
  backendReadyReject = reject
})

if (externalBackendPort !== undefined) {
  waitForPort(externalBackendPort).then(backendReadyResolve, backendReadyReject)
}

const forwardLog = (stream, prefix) => {
  const lines = createInterface({ input: stream })
  lines.on('line', line => {
    process.stdout.write(`${prefix}${line}\n`)
    const match = /^dsh web: http:\/\/127\.0\.0\.1:(\d+)/.exec(line)
    if (match && backendPort === undefined) {
      backendPort = Number(match[1])
      backendReadyResolve(backendPort)
    }
  })
}

if (backend !== undefined) {
  forwardLog(backend.stdout, '[dsh] ')
  forwardLog(backend.stderr, '[dsh:err] ')
  backend.once('error', backendReadyReject)
  backend.once('exit', (code, signal) => {
    if (backendPort === undefined) backendReadyReject(new Error(`DSH exited before readiness: code=${code} signal=${signal}`))
    gateway.close(() => process.exit(code ?? 1))
  })
}

const gateway = http.createServer(async (request, response) => {
  if (!authenticate(request, response)) return
  const port = await backendReady
  const headers = backendHeaders(request.headers, port)
  const proxy = http.request(
    {
      host: LOOPBACK,
      port,
      method: request.method,
      path: request.url,
      headers,
    },
    backendResponse => {
      response.writeHead(backendResponse.statusCode ?? 502, backendResponse.statusMessage, backendResponse.headers)
      backendResponse.pipe(response)
    },
  )
  proxy.on('error', error => {
    if (!response.headersSent) response.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' })
    response.end(`Local DSH proxy failed: ${error.message}`)
  })
  request.pipe(proxy)
})

gateway.on('upgrade', async (request, socket, head) => {
  if (!hasSessionCookie(request)) {
    socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
    return
  }
  try {
    const port = await backendReady
    const upstream = net.connect(port, LOOPBACK, () => {
      const headers = backendHeaders(request.headers, port)
      const headerLines = Object.entries(headers).flatMap(([name, value]) => {
        if (Array.isArray(value)) return value.map(item => `${name}: ${item}`)
        return value === undefined ? [] : [`${name}: ${value}`]
      })
      upstream.write(`${request.method} ${request.url} HTTP/${request.httpVersion}\r\n${headerLines.join('\r\n')}\r\n\r\n`)
      if (head.length > 0) upstream.write(head)
      socket.pipe(upstream).pipe(socket)
    })
    upstream.on('error', () => socket.destroy())
  } catch {
    socket.destroy()
  }
})

function authenticate(request, response) {
  if (hasSessionCookie(request)) return true
  const host = request.headers.host ?? `${LOOPBACK}:0`
  const url = new URL(request.url ?? '/', `http://${host}`)
  const supplied = url.searchParams.get('token')
  if (!safeEquals(supplied, token)) {
    response.writeHead(401, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
    response.end('A valid DSH Mobile launch token is required.')
    return false
  }
  url.searchParams.delete('token')
  response.writeHead(302, {
    location: `${url.pathname}${url.search}${url.hash}`,
    'set-cookie': `${COOKIE_NAME}=${token}; HttpOnly; SameSite=Strict; Path=/`,
    'cache-control': 'no-store',
    'referrer-policy': 'no-referrer',
  })
  response.end()
  return false
}

function hasSessionCookie(request) {
  const cookie = request.headers.cookie ?? ''
  const supplied = cookie.split(';').map(item => item.trim()).find(item => item.startsWith(`${COOKIE_NAME}=`))?.slice(COOKIE_NAME.length + 1)
  return safeEquals(supplied, token)
}

function safeEquals(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false
  const leftBytes = Buffer.from(left)
  const rightBytes = Buffer.from(right)
  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes)
}

function backendHeaders(headers, port) {
  const rewritten = { ...headers, host: `${LOOPBACK}:${port}` }
  delete rewritten.cookie
  if (rewritten.origin !== undefined) rewritten.origin = `http://${LOOPBACK}:${port}`
  return rewritten
}

function parsePort(value) {
  if (value === undefined) return undefined
  const port = Number(value)
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('DSH_MOBILE_BACKEND_PORT must be a TCP port')
  return port
}

async function waitForPort(port) {
  const deadline = Date.now() + 85_000
  while (Date.now() < deadline) {
    if (await canConnect(port)) return port
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`DSH backend did not listen on ${LOOPBACK}:${port}`)
}

function canConnect(port) {
  return new Promise(resolve => {
    const socket = net.connect(port, LOOPBACK)
    socket.once('connect', () => {
      socket.destroy()
      resolve(true)
    })
    socket.once('error', () => resolve(false))
  })
}

const shutdown = signal => {
  gateway.close()
  if (backend !== undefined && !backend.killed) backend.kill(signal)
  setTimeout(() => process.exit(0), 3000).unref()
}
process.on('SIGINT', () => shutdown('SIGINT'))
process.on('SIGTERM', () => shutdown('SIGTERM'))

const port = await backendReady
gateway.listen(0, LOOPBACK, () => {
  const address = gateway.address()
  if (typeof address !== 'object' || address === null) throw new Error('Gateway did not obtain a TCP port')
  process.stdout.write(`dsh-mobile gateway: http://${LOOPBACK}:${address.port}\n`)
})
