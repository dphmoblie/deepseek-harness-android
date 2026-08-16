import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import http from 'node:http'
import net from 'node:net'
import { after, before, describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const TOKEN = 'A'.repeat(43)
const USERNAME = 'dsh-mobile'
const PRELOAD = fileURLToPath(new URL('./mobile-auth-preload.cjs', import.meta.url))
const CHILD_SOURCE = `
  if (process.env.DSH_MOBILE_AUTH_TOKEN !== undefined || process.env.NODE_OPTIONS !== undefined) {
    throw new Error('one-time authentication environment was not cleared')
  }
  const http = require('node:http')
  const server = http.createServer((_request, response) => response.end('authorized'))
  server.on('upgrade', (_request, socket) => {
    socket.end('HTTP/1.1 101 Switching Protocols\\r\\nConnection: Upgrade\\r\\nUpgrade: websocket\\r\\n\\r\\n')
  })
  server.listen(0, '127.0.0.1', () => process.stdout.write(String(server.address().port) + '\\n'))
  process.on('SIGTERM', () => server.close(() => process.exit(0)))
`

function authorizationHeader() {
  return `Basic ${Buffer.from(`${USERNAME}:${TOKEN}`, 'ascii').toString('base64')}`
}

function request(port, authorization) {
  return new Promise((resolve, reject) => {
    const headers = authorization === undefined ? {} : { Authorization: authorization }
    const outgoing = http.get({ host: '127.0.0.1', port, path: '/', headers }, response => {
      const chunks = []
      response.on('data', chunk => chunks.push(chunk))
      response.on('end', () => resolve({
        body: Buffer.concat(chunks).toString('utf8'),
        headers: response.headers,
        status: response.statusCode,
      }))
    })
    outgoing.on('error', reject)
  })
}

function upgrade(port, authorization) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port })
    let response = ''
    socket.setEncoding('ascii')
    socket.on('connect', () => {
      const authLine = authorization === undefined ? '' : `Authorization: ${authorization}\r\n`
      socket.write(
        `GET /events HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\n` +
        `Connection: Upgrade\r\nUpgrade: websocket\r\n${authLine}\r\n`,
      )
    })
    socket.on('data', chunk => { response += chunk })
    socket.on('end', () => resolve(response))
    socket.on('error', reject)
  })
}

describe('mobile Harness authentication preload', () => {
  let child
  let port

  before(async () => {
    child = spawn(process.execPath, ['-e', CHILD_SOURCE], {
      env: {
        ...process.env,
        DSH_MOBILE_AUTH_TOKEN: TOKEN,
        NODE_OPTIONS: `--require=${PRELOAD}`,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    const line = await new Promise((resolve, reject) => {
      let output = ''
      child.stdout.setEncoding('ascii')
      child.stdout.on('data', chunk => {
        output += chunk
        if (output.includes('\n')) resolve(output.slice(0, output.indexOf('\n')))
      })
      child.once('error', reject)
      child.once('exit', code => reject(new Error(`authentication fixture exited with ${code}`)))
    })
    port = Number(line)
    assert.ok(Number.isInteger(port) && port > 0)
  })

  after(async () => {
    if (child === undefined || child.exitCode !== null) return
    child.kill('SIGTERM')
    await new Promise(resolve => child.once('exit', resolve))
  })

  it('challenges unauthenticated HTTP and accepts the exact credential', async () => {
    const denied = await request(port)
    assert.equal(denied.status, 401)
    assert.equal(denied.body, '')
    assert.equal(
      denied.headers['www-authenticate'],
      'Basic realm="DeepSeek Harness Mobile", charset="UTF-8"',
    )

    const accepted = await request(port, authorizationHeader())
    assert.equal(accepted.status, 200)
    assert.equal(accepted.body, 'authorized')
  })

  it('guards WebSocket upgrades with the same credential', async () => {
    assert.match(await upgrade(port), /^HTTP\/1\.1 401 Unauthorized/)
    assert.match(await upgrade(port, authorizationHeader()), /^HTTP\/1\.1 101 Switching Protocols/)
  })
})
