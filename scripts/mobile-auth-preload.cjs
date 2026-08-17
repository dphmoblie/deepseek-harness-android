'use strict'

const crypto = require('node:crypto')
const http = require('node:http')

const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/
const USERNAME = 'dsh-mobile'
const REALM = 'DeepSeek Harness Mobile'
const TOKEN_COOKIE = 'dsh_mobile_token'
const token = process.env.DSH_MOBILE_AUTH_TOKEN

if (typeof token !== 'string' || !TOKEN_PATTERN.test(token)) {
  throw new Error('mobile Harness authentication is unavailable')
}

const expectedToken = Buffer.from(token, 'ascii')
const expected = Buffer.from(
  `Basic ${Buffer.from(`${USERNAME}:${token}`, 'ascii').toString('base64')}`,
  'ascii',
)
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
