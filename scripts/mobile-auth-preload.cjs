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
const MOBILE_TOKEN_PARAM = 'dshMobileToken'

/*
 * dsh web 启动时会把带自己令牌的地址打到 stdout（`dsh web: http://127.0.0.1:3080/?token=…`），
 * 并在那层做二次鉴权。外置浏览器必须同时满足两层：我们的 Cookie + dsh 的令牌，
 * 否则页面只会显示 "dsh web authentication required; reopen the URL printed by dsh web."
 * 预载和 dsh 同进程，直接截获它打印的那行即可，不必让用户从日志里复制。
 */
let dshToken = null
const captureDshToken = chunk => {
  if (dshToken !== null) return
  const text = typeof chunk === 'string' ? chunk : (Buffer.isBuffer(chunk) ? chunk.toString('utf8') : '')
  if (text.length === 0 || text.indexOf('token=') < 0) return
  const match = /[?&]token=([A-Za-z0-9_-]{20,})/u.exec(text)
  if (match) dshToken = match[1]
}
const writeOriginal = process.stdout.write.bind(process.stdout)
process.stdout.write = function (chunk, encoding, callback) {
  captureDshToken(chunk)
  return writeOriginal(chunk, encoding, callback)
}
const writeErrorOriginal = process.stderr.write.bind(process.stderr)
process.stderr.write = function (chunk, encoding, callback) {
  captureDshToken(chunk)
  return writeErrorOriginal(chunk, encoding, callback)
}
const BROWSER_COOKIE = 'dsh_browser_session'
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
const browserToken = process.env.DSH_MOBILE_BROWSER_TOKEN

if (typeof token !== 'string' || !TOKEN_PATTERN.test(token)) {
  throw new Error('mobile Harness authentication is unavailable')
}
const expectedToken = Buffer.from(token, 'ascii')
/**
 * 外置浏览器用的本机凭据。
 *
 * 与 Basic 密码**分开**：Basic 密码是 WebView 侧的，外置浏览器只需要这一枚。两者都是
 * 每次启动重新生成、随进程消失；分成两枚只是为了不把 Basic 密码写进浏览器地址栏。
 *
 * 这里不再加"一次性/次数/分钟数"之类的限制：回环地址本身就决定了这条 URL 出了设备
 * 就作废，能利用它的只有同机应用，而门已经挡住了没有令牌的同机应用。外置浏览器被当作
 * 完整的 DSH 客户端使用，风险和桌面端打开 http://127.0.0.1 是同一量级，由用户自行
 * 判断他点开的链接与网站。
 */
const expectedBrowserToken = typeof browserToken === 'string' && TOKEN_PATTERN.test(browserToken)
  ? Buffer.from(browserToken, 'ascii')
  : null
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
delete process.env.DSH_MOBILE_BROWSER_TOKEN
delete process.env.NODE_OPTIONS

function isAuthorized(request) {
  const header = request.headers.authorization
  if (typeof header !== 'string' || header.length !== expected.length) return false
  const actual = Buffer.from(header, 'ascii')
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected)
}

function hasValidBrowserCookie(request) {
  if (expectedBrowserToken === null) return false
  const header = request.headers.cookie
  if (typeof header !== 'string') return false
  for (const part of header.split(';')) {
    const index = part.indexOf('=')
    if (index <= 0 || part.slice(0, index).trim() !== BROWSER_COOKIE) continue
    const value = part.slice(index + 1).trim()
    if (!TOKEN_PATTERN.test(value)) continue
    const actual = Buffer.from(value, 'ascii')
    if (actual.length === expectedBrowserToken.length &&
      crypto.timingSafeEqual(actual, expectedBrowserToken)) return true
  }
  return false
}

/**
 * 外置浏览器交接：查询串里的令牌 → HttpOnly Cookie → 302 去掉令牌。
 *
 * 为什么用查询串：片段不会发给服务器，服务器就没法直接兑换。查询串 + 302 只多一次往返，
 * 重定向会替换浏览器历史里的当前条目，所以历史里最终留下的是 `/`，令牌不落历史。
 *
 * 只认根路径与 `/__dsh/browser`：避免把 dsh 自己带 `token=` 的接口请求误当成交接。
 */
function browserHandoff(request, response) {
  if (request.method !== 'GET' || typeof request.url !== 'string') return false
  const queryStart = request.url.indexOf('?')
  if (queryStart < 0) return false
  const pathname = request.url.slice(0, queryStart)
  if (pathname !== '/' && pathname !== '/__dsh/browser') return false
  const params = new URLSearchParams(request.url.slice(queryStart + 1))
  // 参数名必须与 dsh 自己的 ?token= 区分开：dsh web 启动时也会打开带 ?token= 的地址，
  // 抢同一个名字会把它的正常引导当成我们的交接，令牌不匹配就 403（实测「端口打不开/令牌无效」）。
  if (!params.has(MOBILE_TOKEN_PARAM)) return false
  const supplied = params.get(MOBILE_TOKEN_PARAM) ?? ''
  const actual = Buffer.from(supplied, 'ascii')
  const accepted = expectedBrowserToken !== null && actual.length === expectedBrowserToken.length &&
    crypto.timingSafeEqual(actual, expectedBrowserToken)
  if (!accepted) {
    browserNotice(response, 403, '令牌已失效（运行环境重启过，或这个地址是上一次打开留下的）。请回到应用：设置 → 诊断与日志 → 用外部浏览器打开 Harness，重新获取一个地址。')
    return true
  }
  params.delete(MOBILE_TOKEN_PARAM)
  const rest = params.toString()
  /*
   * Location 用**绝对地址**：部分浏览器（尤其国产内核）对相对 Location 的解析不一致，
   * 实测表现为「地址栏已跳到 127.0.0.1，但页面不加载」。Host 头就是浏览器自己请求的
   * 主机名，直接沿用即可；缺失时退回相对路径。
   */
  const host = typeof request.headers.host === 'string' ? request.headers.host : ''
  const params2 = new URLSearchParams(rest)
  if (dshToken !== null) params2.set('token', dshToken)
  const query = params2.toString()
  const target = query === '' ? '/' : `/?${query}`
  const location = /^[A-Za-z0-9.:\[\]-]{1,255}$/.test(host) ? `http://${host}${target}` : target
  response.writeHead(302, {
    'Cache-Control': 'no-store',
    Location: location,
    /*
     * 与运行周期同寿：运行环境一停，令牌换了，这枚 Cookie 自然失效。
     * Max-Age 只用来让浏览器别把它当会话 Cookie 丢掉，真正决定有效性的还是令牌本身。
     */
    'Set-Cookie': `${BROWSER_COOKIE}=${browserToken}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000`,
    'Content-Length': '0',
  })
  response.end()
  return true
}

/** 浏览器带了一枚失效的 Cookie：给一段可读提示，不去弹没人能填的 Basic 框。 */
function browserNotice(response, status, message) {
  const body = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>DeepSeek Harness</title><p style="font:16px/1.6 system-ui,sans-serif;padding:24px;max-width:32em">${message}</p>`
  response.writeHead(status, {
    'Cache-Control': 'no-store',
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  })
  response.end(body)
}

/** 请求里是否带着浏览器会话 Cookie（不管它是否仍然有效）。 */
function hasBrowserCookie(request) {
  const header = request.headers.cookie
  if (typeof header !== 'string') return false
  return header.split(';').some(part => part.slice(0, part.indexOf('=')).trim() === BROWSER_COOKIE)
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
    // 两枚 Cookie 各认各自的令牌：WebView 用 Basic 同款那一枚，外置浏览器用浏览器专用那一枚。
    const expectedValue = name === TOKEN_COOKIE
      ? expectedToken
      : name === BROWSER_COOKIE ? expectedBrowserToken : null
    if (expectedValue === null) continue
    const value = part.slice(index + 1).trim()
    if (!TOKEN_PATTERN.test(value)) continue
    const actual = Buffer.from(value, 'ascii')
    if (actual.length === expectedValue.length && crypto.timingSafeEqual(actual, expectedValue)) return true
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
  if (event === 'request' && browserHandoff(request, args[1])) return true
  if (event === 'request' && !isAuthorized(request) && !hasValidBrowserCookie(request) && !isOAuthCallback(request)) {
    // 带着浏览器 Cookie 却不是当前这枚（运行环境重启过）：给可读提示，而不是 Basic 弹窗。
    if (typeof request.headers.authorization !== 'string' && hasBrowserCookie(request)) {
      browserNotice(args[1], 403, '这次浏览器会话已经失效（运行环境重启过）。请回到应用里重新打开。')
      return true
    }
    rejectRequest(args[1])
    return true
  }
  if (event === 'upgrade' && !isAuthorized(request) && !hasValidCookieToken(request)) {
    rejectUpgrade(args[1])
    return true
  }
  return Reflect.apply(originalEmit, this, [event, ...args])
}
