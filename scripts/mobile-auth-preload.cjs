'use strict'

const crypto = require('node:crypto')
const childProcess = require('node:child_process')
const http = require('node:http')
const fsp = require('node:fs/promises')
const path = require('node:path').posix
const { syncBuiltinESMExports } = require('node:module')
const { promisify } = require('node:util')

const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/
const USERNAME = 'dsh-mobile'
const REALM = 'DeepSeek Harness Mobile'
const TOKEN_COOKIE = 'dsh_mobile_token'
const BROWSER_COOKIE = 'dsh_browser_session'
const BROWSER_BOOTSTRAP_PATH = '/__dsh/browser'
const BROWSER_CLAIM_PATH = '/__dsh/browser/claim'
const SESSION_PUBLISHER = '/opt/python/bin/python3'
const SESSION_PUBLISHER_SCRIPT = '/usr/local/lib/dsh-mobile-session-publish.py'
const SESSION_TARGET_PATTERN = /^\/root\/\.dsh\/sessions\/[^/]{1,255}\/[^/]{1,255}\/session(?:\.v[0-9]+)?\.jsonl(?:\.zstd)?$/
const SESSION_STAGE_PATTERN = /^session\.[A-Za-z0-9._-]{1,192}\.tmp$/
const ATTACHMENT_ROOT = '/root/.dsh/attachments/v1/'
const UUID_PATTERN = '[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'
const ATTACHMENT_STAGE_PATTERN = new RegExp(`^tmp/${UUID_PATTERN}$`)
const OBJECT_PATTERN = /^(objects|file-objects|request-images)\/([a-f0-9]{2})\/([a-f0-9]{64})$/
const ALIAS_PATTERN = /^files\/([a-f0-9]{2})\/([a-f0-9]{64})\/[^/]+$/
const FILE_STAGE_SUFFIX = new RegExp(`^[0-9]{1,10}\\.${UUID_PATTERN}\\.tmpdir$`)
const LINK_FALLBACK_CODES = new Set(['EACCES', 'EPERM', 'ENOTSUP', 'EOPNOTSUPP', 'EMLINK', 'EXDEV'])
// The helper returns Linux errno values, including when tests run on another host OS.
const PUBLISH_ERRORS = { 1: 'EPERM', 2: 'ENOENT', 5: 'EIO', 13: 'EACCES', 17: 'EEXIST', 18: 'EXDEV',
  20: 'ENOTDIR', 22: 'EINVAL', 27: 'EFBIG', 28: 'ENOSPC', 30: 'EROFS', 38: 'ENOSYS', 40: 'ELOOP', 95: 'ENOTSUP' }
const token = process.env.DSH_MOBILE_AUTH_TOKEN
const browserClaimToken = process.env.DSH_MOBILE_BROWSER_CLAIM_TOKEN

if (typeof token !== 'string' || !TOKEN_PATTERN.test(token)) {
  throw new Error('mobile Harness authentication is unavailable')
}
const expectedToken = Buffer.from(token, 'ascii')
const expectedBrowserClaimToken = typeof browserClaimToken === 'string' && TOKEN_PATTERN.test(browserClaimToken)
  ? Buffer.from(browserClaimToken, 'ascii')
  : null
/**
 * 一次运行周期里允许兑换几次接管令牌。
 *
 * 不是随便放开的：每次兑换都会把同一枚访客令牌种进浏览器 Cookie 罐，次数越多暴露面越大。
 * 但也确实需要重试——用户可能关掉浏览器、换一个浏览器、或者第一次打开失败，所以给 3 次，
 * 而不是「一次失败就只能重启运行时」。
 */
const BROWSER_CLAIM_LIMIT = 3
/** 接管后的浏览器会话有效期；过期后需要回到应用里重新点一次诊断入口。 */
const BROWSER_SESSION_MS = 10 * 60_000
let browserClaims = 0
let browserClaimExpiresAt = 0
let browserSessionExpiresAt = 0
const expected = Buffer.from(
  `Basic ${Buffer.from(`${USERNAME}:${token}`, 'ascii').toString('base64')}`,
  'ascii',
)

function canonicalPath(value) {
  return typeof value === 'string' && value.startsWith('/') && Buffer.byteLength(value) <= 4096 &&
    !/[\x00-\x1f\x7f]/u.test(value) && value.slice(1).split('/').every(part =>
      part !== '' && part !== '.' && part !== '..' && Buffer.byteLength(part) <= 255)
}

// 安全校验点：仅匹配锁定版本 DSH 的三种发布布局；任意 link 调用不改变语义。
// Python 再次校验路径，并以不跟随符号链接的目录句柄执行，不经过 Shell。
function isDshPublication(source, target) {
  if (!canonicalPath(source) || !canonicalPath(target) || source === target) return false
  if (path.dirname(source) === path.dirname(target) && SESSION_STAGE_PATTERN.test(path.basename(source)) &&
    SESSION_TARGET_PATTERN.test(target)) return true
  if (source.startsWith(ATTACHMENT_ROOT) && target.startsWith(ATTACHMENT_ROOT)) {
    const from = source.slice(ATTACHMENT_ROOT.length)
    const to = target.slice(ATTACHMENT_ROOT.length)
    const object = OBJECT_PATTERN.exec(to)
    if (ATTACHMENT_STAGE_PATTERN.test(from) && object && object[2] === object[3].slice(0, 2)) return true
    const original = OBJECT_PATTERN.exec(from)
    const alias = ALIAS_PATTERN.exec(to)
    return original?.[1] === 'file-objects' && alias !== null && original[2] === original[3].slice(0, 2) &&
      alias[1] === original[2] && alias[2] === original[3]
  }
  const staging = path.dirname(source)
  const prefix = `.${path.basename(target)}.`
  return path.dirname(staging) === path.dirname(target) && path.basename(source) === `${path.basename(target)}.tmp` &&
    path.basename(staging).startsWith(prefix) && FILE_STAGE_SUFFIX.test(path.basename(staging).slice(prefix.length))
}

// PRoot may reject hard links. Session publication retains its existing rename
// path; attachments/new files use a synced private copy followed by no-replace
// rename, preserving the source and never exposing a partially copied target.
// syncBuiltinESMExports updates DSH's named fs/promises import.
const execFile = promisify(childProcess.execFile)
const originalLink = fsp.link.bind(fsp)
fsp.link = async (source, target) => {
  try {
    return await originalLink(source, target)
  } catch (error) {
    const code = error && typeof error === 'object' ? error.code : undefined
    if (!LINK_FALLBACK_CODES.has(code) || !isDshPublication(source, target)) throw error
    try {
      await execFile(SESSION_PUBLISHER, ['-I', SESSION_PUBLISHER_SCRIPT, source, target], {
        timeout: 60_000,
        maxBuffer: 4096,
        windowsHide: true,
      })
    } catch (publishError) {
      // Only return a controlled errno; subprocess output may contain private paths.
      const failure = new Error('mobile file publication failed')
      failure.code = publishError?.killed ? 'ETIMEDOUT' : PUBLISH_ERRORS[publishError?.code] ?? 'EIO'
      throw failure
    }
  }
}
syncBuiltinESMExports()

delete process.env.DSH_MOBILE_AUTH_TOKEN
delete process.env.DSH_MOBILE_BROWSER_CLAIM_TOKEN
delete process.env.NODE_OPTIONS

function isAuthorized(request) {
  const header = request.headers.authorization
  if (typeof header !== 'string' || header.length !== expected.length) return false
  const actual = Buffer.from(header, 'ascii')
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected)
}

function hasValidBrowserCookie(request) {
  if (browserSessionExpiresAt <= Date.now()) return false
  const header = request.headers.cookie
  if (typeof header !== 'string') return false
  for (const part of header.split(';')) {
    const index = part.indexOf('=')
    if (index <= 0 || part.slice(0, index).trim() !== BROWSER_COOKIE) continue
    const value = part.slice(index + 1).trim()
    if (!TOKEN_PATTERN.test(value)) continue
    const actual = Buffer.from(value, 'ascii')
    if (actual.length === expectedToken.length && crypto.timingSafeEqual(actual, expectedToken)) return true
  }
  return false
}

function browserBootstrap(response) {
  const body = `<!doctype html><meta charset="utf-8"><title>DeepSeek Harness</title><script>
const token = location.hash.startsWith('#token=') ? location.hash.slice(7) : '';
history.replaceState(null, '', location.pathname + location.search);
if (!/^[A-Za-z0-9_-]{43}$/.test(token)) { document.body.textContent = '外部浏览器接管令牌无效或已过期'; }
else fetch('/__dsh/browser/claim', { method: 'POST', headers: { 'content-type': 'text/plain' }, body: token, credentials: 'same-origin' })
  .then(result => { if (!result.ok) throw new Error('claim failed'); location.replace('/'); })
  .catch(() => { document.body.textContent = '外部浏览器接管失败，请返回应用重试'; });
</script>`
  response.writeHead(200, {
    'Cache-Control': 'no-store',
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  })
  response.end(body)
}

function browserClaim(request, response, chunks) {
  if (request.method !== 'POST' || request.url !== BROWSER_CLAIM_PATH) return false
  if (!expectedBrowserClaimToken || browserClaims >= BROWSER_CLAIM_LIMIT || browserClaimExpiresAt <= Date.now()) {
    response.writeHead(410, { 'Cache-Control': 'no-store', 'Content-Length': '0' })
    response.end()
    return true
  }
  const supplied = Buffer.concat(chunks).toString('ascii')
  const actual = Buffer.from(supplied, 'ascii')
  if (actual.length !== expectedBrowserClaimToken.length || !crypto.timingSafeEqual(actual, expectedBrowserClaimToken)) {
    response.writeHead(401, { 'Cache-Control': 'no-store', 'Content-Length': '0' })
    response.end()
    return true
  }
  browserClaims += 1
  browserSessionExpiresAt = Date.now() + BROWSER_SESSION_MS
  response.writeHead(204, {
    'Cache-Control': 'no-store',
    /*
     * 只种浏览器专用那一枚。Basic 同款令牌（TOKEN_COOKIE）留给 WebView：浏览器侧不需要它，
     * 多授一枚只会扩大暴露面——两枚令牌本来就是同一个值。
     */
    'Set-Cookie': `${BROWSER_COOKIE}=${token}; Path=/; Max-Age=${BROWSER_SESSION_MS / 1000}; HttpOnly; SameSite=Strict`,
    'Content-Length': '0',
  })
  response.end()
  return true
}

// DeepSeek account login completes in the Harness server itself.  The external
// browser cannot know the per-launch Basic Auth token, so only the provider's
// exact GET callback is allowed through this app-level gate.  The Harness
// provider still validates state, code, method, and replay protection.
function isOAuthCallback(request) {
  return request.method === 'GET' && typeof request.url === 'string' &&
    request.url.split('?', 1)[0] === '/oauth/callback'
}

// Android WebView 的 WS 握手 401 不触发 onReceivedHttpAuthRequest，Basic 挑战
// 对 WebSocket 无效；因此 upgrade 额外接受 HttpOnly Cookie 承载的同一 token
//（由 HarnessActivity 在加载前注入，JS 不可读）。遍历全部同名 cookie，任一
// 匹配即通过，防止 JS 伪造同名 cookie 挤占真 token 造成拒绝服务。
function hasValidCookieToken(request) {
  const header = request.headers.cookie
  if (typeof header !== 'string') return false
  for (const part of header.split(';')) {
    const index = part.indexOf('=')
    if (index <= 0) continue
    const name = part.slice(0, index).trim()
    if (name !== TOKEN_COOKIE && name !== BROWSER_COOKIE) continue
    if (name === BROWSER_COOKIE && browserSessionExpiresAt <= Date.now()) continue
    const value = part.slice(index + 1).trim()
    if (!TOKEN_PATTERN.test(value)) continue
    const actual = Buffer.from(value, 'ascii')
    if (actual.length === expectedToken.length && crypto.timingSafeEqual(actual, expectedToken)) {
      return true
    }
  }
  return false
}

function rejectRequest(response) {
  response.writeHead(401, {
    'Cache-Control': 'no-store',
    'Content-Length': '0',
    'WWW-Authenticate': `Basic realm="${REALM}", charset="UTF-8"`,
  })
  response.end()
}

function rejectUpgrade(socket) {
  socket.end(
    `HTTP/1.1 401 Unauthorized\r\n` +
    `Connection: close\r\n` +
    `Content-Length: 0\r\n` +
    `WWW-Authenticate: Basic realm="${REALM}"\r\n\r\n`,
  )
}

// Enforce authentication before any Harness route or WebSocket listener runs.
// HTTP 请求仅接受 Basic 凭据；WS upgrade 额外接受 Cookie token（WebView 场景）。
const originalEmit = http.Server.prototype.emit
http.Server.prototype.emit = function authenticatedEmit(event, ...args) {
  const request = args[0]
  if (event === 'request' && request?.method === 'GET' && request.url === BROWSER_BOOTSTRAP_PATH) {
    browserClaimExpiresAt = Date.now() + 60_000
    browserBootstrap(args[1])
    return true
  }
  if (event === 'request' && request?.method === 'POST' && request.url === BROWSER_CLAIM_PATH) {
    const response = args[1]
    const chunks = []
    let bytes = 0
    let oversized = false
    request.on('data', chunk => {
      const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      bytes += buffer.length
      if (bytes > expectedToken.length) oversized = true
      if (bytes <= expectedToken.length) chunks.push(buffer)
    })
    request.on('end', () => browserClaim(request, response, oversized ? [Buffer.from('oversized', 'ascii')] : chunks))
    return true
  }
  if (event === 'request' && !isAuthorized(request) && !hasValidBrowserCookie(request) && !isOAuthCallback(request)) {
    rejectRequest(args[1])
    return true
  }
  if (event === 'upgrade' && !isAuthorized(request) && !hasValidCookieToken(request)) {
    rejectUpgrade(args[1])
    return true
  }
  return Reflect.apply(originalEmit, this, [event, ...args])
}
