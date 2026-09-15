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

if (typeof token !== 'string' || !TOKEN_PATTERN.test(token)) {
  throw new Error('mobile Harness authentication is unavailable')
}

const expectedToken = Buffer.from(token, 'ascii')
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
delete process.env.NODE_OPTIONS

function isAuthorized(request) {
  const header = request.headers.authorization
  if (typeof header !== 'string' || header.length !== expected.length) return false
  const actual = Buffer.from(header, 'ascii')
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected)
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
    if (part.slice(0, index).trim() !== TOKEN_COOKIE) continue
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
  if (event === 'request' && !isAuthorized(request)) {
    rejectRequest(args[1])
    return true
  }
  if (event === 'upgrade' && !isAuthorized(request) && !hasValidCookieToken(request)) {
    rejectUpgrade(args[1])
    return true
  }
  return Reflect.apply(originalEmit, this, [event, ...args])
}
