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

describe('mobile DSH publication fallback', () => {
  let fixtureDirectory
  let failurePreload
  const uuid = '01234567-89ab-4cde-8f01-23456789abcd'
  const digest = 'ab'.repeat(32)
  const attachments = '/root/.dsh/attachments/v1'
  const session = {
    source: '/root/.dsh/sessions/project/session/session.v3.jsonl.zstd.0123456789ab.tmp',
    target: '/root/.dsh/sessions/project/session/session.v3.jsonl.zstd',
  }
  const workspace = name => ({
    source: `/root/project/.${name}.123.${uuid}.tmpdir/${name}.tmp`, target: `/root/project/${name}`,
  })

  before(async () => {
    fixtureDirectory = await mkdtemp(join(tmpdir(), 'dsh-mobile-link-'))
    failurePreload = join(fixtureDirectory, 'force-link-failure.cjs')
    await writeFile(failurePreload, `
      'use strict'
      const fixture = JSON.parse(process.env.DSH_MOBILE_LINK_TEST)
      global.publisherCalls = 0
      const fsp = require('node:fs/promises')
      fsp.link = async () => {
        if (fixture.linkCode === null) return
        const error = new Error('forced Android PRoot hard-link failure')
        error.code = fixture.linkCode
        throw error
      }
      require('node:child_process').execFile = (file, args, options, callback) => {
        global.publisherCalls++
        const valid = file === '/opt/python/bin/python3' &&
          args[0] === '-I' && args[1] === '/usr/local/lib/dsh-mobile-session-publish.py' &&
          args[2] === fixture.source && args[3] === fixture.target && args.length === 4 &&
          options.timeout === 60000 && options.maxBuffer === 4096 && options.windowsHide === true
        if (!valid) {
          const error = new Error('invalid publisher invocation')
          error.code = 5
          callback(error, '', '')
          return
        }
        if (fixture.helperCode || fixture.timeout) {
          const error = new Error('private subprocess output must not escape')
          error.code = fixture.helperCode
          error.killed = fixture.timeout
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

  async function runFallback(fixture = {}) {
    const input = { ...session, linkCode: 'EACCES', ...fixture }
    const childSource = `
      (async () => {
        const { link } = await import('node:fs/promises')
        const fixture = JSON.parse(process.env.DSH_MOBILE_LINK_TEST)
        let result = 'published'
        try {
          await link(fixture.source, fixture.target)
        } catch (error) {
          if (error.message.includes('private subprocess')) throw error
          result = error.code
        }
        process.stdout.write(JSON.stringify({ result, calls: global.publisherCalls }))
      })().catch(() => process.exit(2))
    `
    const child = spawn(process.execPath, ['-e', childSource], {
      env: {
        ...process.env,
        DSH_MOBILE_AUTH_TOKEN: TOKEN,
        DSH_MOBILE_LINK_TEST: JSON.stringify(input),
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
    return JSON.parse(Buffer.concat(stdout).toString('utf8'))
  }

  it('uses the bounded no-replace publisher after a PRoot permission failure', async () => {
    assert.deepEqual(await runFallback(), { result: 'published', calls: 1 })
  })

  it('preserves EEXIST when another writer published first', async () => {
    assert.deepEqual(await runFallback({ helperCode: 17 }), { result: 'EEXIST', calls: 1 })
  })

  for (const bucket of ['objects', 'file-objects', 'request-images']) {
    it(`publishes staged attachment ${bucket} through the ESM link import`, async () => {
      assert.deepEqual(await runFallback({ source: `${attachments}/tmp/${uuid}`, target: `${attachments}/${bucket}/ab/${digest}` }),
        { result: 'published', calls: 1 })
    })
  }
  it('publishes immutable file aliases with names passed as literal arguments', async () => {
    assert.deepEqual(await runFallback({ source: `${attachments}/file-objects/ab/${digest}`,
      target: `${attachments}/files/ab/${digest}/报告 $() ' name.txt` }), { result: 'published', calls: 1 })
  })
  it('publishes new workspace files, including Unicode and shell metacharacters', async () => {
    assert.deepEqual(await runFallback(workspace("报告 $() ' name.txt")), { result: 'published', calls: 1 })
  })
  for (const linkCode of ['EPERM', 'ENOTSUP', 'EOPNOTSUPP', 'EMLINK', 'EXDEV']) {
    it(`allows the bounded fallback for ${linkCode}`, async () => {
      assert.deepEqual(await runFallback({ ...workspace('new.txt'), linkCode }), { result: 'published', calls: 1 })
    })
  }
  for (const linkCode of [null, 'EEXIST', 'ENOENT', 'ENOSPC', 'EIO', 'EROFS']) {
    it(`does not invoke a fallback after ${linkCode ?? 'successful link'}`, async () => {
      assert.deepEqual(await runFallback({ linkCode }), { result: linkCode ?? 'published', calls: 0 })
    })
  }
  for (const [helperCode, result] of [[28, 'ENOSPC'], [38, 'ENOSYS'], [40, 'ELOOP'], [13, 'EACCES'], [999, 'EIO']]) {
    it(`returns controlled ${result} without leaking helper output`, async () => {
      assert.deepEqual(await runFallback({ helperCode }), { result, calls: 1 })
    })
  }
  it('reports helper timeout without a second lossy fallback', async () => {
    assert.deepEqual(await runFallback({ timeout: true }), { result: 'ETIMEDOUT', calls: 1 })
  })
  const invalid = [
    { source: '/root/a', target: '/root/b' },
    { ...workspace('new.txt'), target: '/root/elsewhere/new.txt' },
    { ...workspace('new.txt'), source: workspace('new.txt').source.replace('.123.', '.bad.') },
    { source: `${attachments}/tmp/${uuid}`, target: `${attachments}/objects/cd/${digest}` },
    { source: `${attachments}/file-objects/ab/${digest}`, target: `${attachments}/files/cd/${'cd'.repeat(32)}/a` },
    { ...session, source: session.source.replace('/project/', '/../') },
    { ...session, target: `${session.target}\n` },
    { ...session, source: session.source.replace('/project/', '//project/') },
    { ...workspace('a'.repeat(256)) },
    { ...workspace('界'.repeat(90)) },
    { ...session, source: null },
  ]
  invalid.forEach((fixture, index) => {
    it(`leaves unsupported or malformed publication ${index + 1} untouched`, async () => {
      assert.deepEqual(await runFallback(fixture), { result: 'EACCES', calls: 0 })
    })
  })
})
