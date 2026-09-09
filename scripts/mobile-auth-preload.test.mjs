import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import http from 'node:http'
import net from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
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

function upgrade(port, options = {}) {
  const { authorization, cookie } = options
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port })
    let response = ''
    socket.setEncoding('ascii')
    socket.on('connect', () => {
      const lines = []
      if (authorization !== undefined) lines.push(`Authorization: ${authorization}`)
      if (cookie !== undefined) lines.push(`Cookie: ${cookie}`)
      socket.write(
        `GET /events HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\n` +
        `Connection: Upgrade\r\nUpgrade: websocket\r\n${lines.join('\r\n')}\r\n\r\n`,
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
    assert.match(await upgrade(port, { authorization: authorizationHeader() }), /^HTTP\/1\.1 101 Switching Protocols/)
  })

  it('accepts the token cookie on upgrades but keeps HTTP Basic-only', async () => {
    // 正确 Cookie：upgrade 放行（WebView 场景）；HTTP 请求不认 Cookie，仍 401
    assert.match(
      await upgrade(port, { cookie: `dsh_mobile_token=${TOKEN}` }),
      /^HTTP\/1\.1 101 Switching Protocols/,
    )
    const httpWithCookie = await new Promise((resolve, reject) => {
      const outgoing = http.get(
        { host: '127.0.0.1', port, path: '/', headers: { Cookie: `dsh_mobile_token=${TOKEN}` } },
        response => { response.resume(); resolve(response.statusCode) },
      )
      outgoing.on('error', reject)
    })
    assert.equal(httpWithCookie, 401)

    // 错误 Cookie 一律拒绝，即使真 token 同时出现在其他同名 cookie 之后
    assert.match(
      await upgrade(port, { cookie: `dsh_mobile_token=${'B'.repeat(43)}` }),
      /^HTTP\/1\.1 401 Unauthorized/,
    )
    assert.match(
      await upgrade(port, { cookie: `dsh_mobile_token=${'B'.repeat(43)}; dsh_mobile_token=${TOKEN}` }),
      /^HTTP\/1\.1 101 Switching Protocols/,
    )
    // 非法格式（长度不符/含不允许字符）拒绝而非崩溃
    assert.match(
      await upgrade(port, { cookie: 'dsh_mobile_token=short' }),
      /^HTTP\/1\.1 401 Unauthorized/,
    )
  })
})

describe('mobile session publication fallback', () => {
  let fixtureDirectory
  let failurePreload

  before(async () => {
    fixtureDirectory = await mkdtemp(join(tmpdir(), 'dsh-mobile-link-'))
    failurePreload = join(fixtureDirectory, 'force-link-failure.cjs')
    await writeFile(failurePreload, `
      'use strict'
      const fsp = require('node:fs/promises')
      fsp.link = async () => {
        const error = new Error('forced Android PRoot hard-link failure')
        error.code = 'EACCES'
        throw error
      }
      require('node:child_process').execFile = (file, args, _options, callback) => {
        const valid = file === '/opt/python/bin/python3' &&
          args[0] === '/usr/local/lib/dsh-mobile-session-publish.py' &&
          args[1] === '/root/.dsh/sessions/project/session/session.v3.jsonl.zstd.0123456789ab.tmp' &&
          args[2] === '/root/.dsh/sessions/project/session/session.v3.jsonl.zstd'
        if (!valid) {
          const error = new Error('invalid publisher invocation')
          error.code = 5
          callback(error, '', '')
          return
        }
        if (process.env.DSH_MOBILE_LINK_TEST_MODE === 'collision') {
          const error = new Error('target exists')
          error.code = 17
          callback(error, '', '')
        } else {
          callback(null, '', '')
        }
      }
    `, { mode: 0o600 })
  })

  after(async () => {
    if (fixtureDirectory !== undefined) await rm(fixtureDirectory, { recursive: true, force: true })
  })

  async function runFallback(mode) {
    const source = '/root/.dsh/sessions/project/session/session.v3.jsonl.zstd.0123456789ab.tmp'
    const target = '/root/.dsh/sessions/project/session/session.v3.jsonl.zstd'
    const childSource = `
      (async () => {
        const { link } = await import('node:fs/promises')
        try {
          await link(${JSON.stringify(source)}, ${JSON.stringify(target)})
          process.stdout.write('published')
        } catch (error) {
          process.stdout.write(String(error.code))
        }
      })().catch(() => process.exit(2))
    `
    const child = spawn(process.execPath, ['-e', childSource], {
      env: {
        ...process.env,
        DSH_MOBILE_AUTH_TOKEN: TOKEN,
        DSH_MOBILE_LINK_TEST_MODE: mode,
        NODE_OPTIONS: `--require=${failurePreload} --require=${PRELOAD}`,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    const stdout = []
    const stderr = []
    child.stdout.on('data', chunk => stdout.push(chunk))
    child.stderr.on('data', chunk => stderr.push(chunk))
    const exitCode = await new Promise((resolve, reject) => {
      child.once('error', reject)
      child.once('exit', resolve)
    })
    assert.equal(exitCode, 0, Buffer.concat(stderr).toString('utf8'))
    return Buffer.concat(stdout).toString('utf8')
  }

  it('uses the bounded no-replace publisher after a PRoot permission failure', async () => {
    assert.equal(await runFallback('success'), 'published')
  })

  it('preserves EEXIST when another writer published first', async () => {
    assert.equal(await runFallback('collision'), 'EEXIST')
  })
})
