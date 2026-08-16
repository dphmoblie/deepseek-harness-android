'use strict'

const crypto = require('node:crypto')
const http = require('node:http')

const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/
const USERNAME = 'dsh-mobile'
const REALM = 'DeepSeek Harness Mobile'
const token = process.env.DSH_MOBILE_AUTH_TOKEN

if (typeof token !== 'string' || !TOKEN_PATTERN.test(token)) {
  throw new Error('mobile Harness authentication is unavailable')
}

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
const originalEmit = http.Server.prototype.emit
http.Server.prototype.emit = function authenticatedEmit(event, ...args) {
  if ((event === 'request' || event === 'upgrade') && !isAuthorized(args[0])) {
    if (event === 'request') rejectRequest(args[1])
    else rejectUpgrade(args[1])
    return true
  }
  return Reflect.apply(originalEmit, this, [event, ...args])
}
