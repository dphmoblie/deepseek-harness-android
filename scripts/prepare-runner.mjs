import { createHash } from 'node:crypto'
import { lookup as dnsLookup } from 'node:dns'
import { createReadStream, createWriteStream } from 'node:fs'
import { chmod, mkdir, open, rename, rm, stat } from 'node:fs/promises'
import { get } from 'node:https'
import { BlockList, isIP } from 'node:net'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const MAX_REDIRECTS = 3
const MAX_RUNNER_BYTES = 16 * 1024 * 1024
const OUTPUT_PATH = resolve('android/app/src/main/jniLibs/arm64-v8a/libdsh_proot.so')
const SHA256_PATTERN = /^[a-f0-9]{64}$/
const BLOCKED_ADDRESSES = new BlockList()
const ipv4 = (...octets) => octets.join('.')

for (const [network, prefix] of [
  [ipv4(0, 0, 0, 0), 8],
  [ipv4(10, 0, 0, 0), 8],
  [ipv4(100, 64, 0, 0), 10],
  [ipv4(127, 0, 0, 0), 8],
  [ipv4(169, 254, 0, 0), 16],
  [ipv4(172, 16, 0, 0), 12],
  [ipv4(192, 168, 0, 0), 16],
  [ipv4(198, 18, 0, 0), 15],
  [ipv4(224, 0, 0, 0), 4],
]) BLOCKED_ADDRESSES.addSubnet(network, prefix, 'ipv4')

for (const [network, prefix] of [
  ['::', 128],
  ['::1', 128],
  ['fc00::', 7],
  ['fe80::', 10],
  ['ff00::', 8],
]) BLOCKED_ADDRESSES.addSubnet(network, prefix, 'ipv6')

function normalizeHost(host) {
  return host.trim().toLowerCase().replace(/^\[|\]$/g, '').replace(/\.+$/, '')
}

export function isPublicAddress(address, family = isIP(address)) {
  const normalizedFamily = family === 4 || family === 'IPv4' || family === 'ipv4' ? 'ipv4'
    : family === 6 || family === 'IPv6' || family === 'ipv6' ? 'ipv6'
      : null
  if (normalizedFamily === null || (normalizedFamily === 'ipv6' && address.toLowerCase().startsWith('::ffff:'))) {
    return false
  }
  return !BLOCKED_ADDRESSES.check(address, normalizedFamily)
}

function isDisallowedHost(host) {
  const normalized = normalizeHost(host)
  return normalized === 'localhost'
    || normalized.endsWith('.localhost')
    || normalized.endsWith('.local')
    || (isIP(normalized) !== 0 && !isPublicAddress(normalized))
}

function publicOnlyLookup(hostname, options, callback) {
  dnsLookup(hostname, { all: true, verbatim: true }, (error, addresses) => {
    if (error !== null) {
      callback(error)
      return
    }
    if (addresses.length === 0 || addresses.some(({ address, family }) => !isPublicAddress(address, family))) {
      callback(new Error('Runner host did not resolve exclusively to public addresses'))
      return
    }
    const requestedFamily = typeof options === 'number' ? options : options?.family ?? 0
    const candidates = requestedFamily === 0
      ? addresses
      : addresses.filter(({ family }) => family === requestedFamily || `IPv${family}` === requestedFamily)
    if (candidates.length === 0) {
      callback(new Error('Runner host did not resolve for the requested address family'))
      return
    }
    if (typeof options === 'object' && options?.all === true) {
      callback(null, candidates)
    } else {
      callback(null, candidates[0].address, candidates[0].family)
    }
  })
}

export function parseRunnerConfig(environment) {
  const rawUrl = environment.DSH_PROOT_ARM64_URL?.trim() ?? ''
  const expectedSha256 = environment.DSH_PROOT_ARM64_SHA256?.trim().toLowerCase() ?? ''
  const expectedBytes = Number(environment.DSH_PROOT_ARM64_BYTES)
  const allowedHosts = new Set(
    (environment.DSH_RUNNER_ALLOWED_HOSTS ?? '')
      .split(',')
      .map(normalizeHost)
      .filter(Boolean),
  )

  let sourceUrl
  try {
    sourceUrl = new URL(rawUrl)
  } catch {
    throw new Error('DSH_PROOT_ARM64_URL must be a valid HTTPS URL')
  }
  if (sourceUrl.protocol !== 'https:' || sourceUrl.username !== '' || sourceUrl.password !== '') {
    throw new Error('DSH_PROOT_ARM64_URL must use HTTPS and must not contain credentials')
  }
  if (isDisallowedHost(sourceUrl.hostname)) {
    throw new Error('DSH_PROOT_ARM64_URL must not target a local or private host')
  }
  if (sourceUrl.hash !== '') throw new Error('DSH_PROOT_ARM64_URL must not contain a fragment')
  if (!SHA256_PATTERN.test(expectedSha256)) {
    throw new Error('DSH_PROOT_ARM64_SHA256 must be 64 lowercase hexadecimal characters')
  }
  if (!Number.isSafeInteger(expectedBytes) || expectedBytes < 1 || expectedBytes > MAX_RUNNER_BYTES) {
    throw new Error(`DSH_PROOT_ARM64_BYTES must be an integer from 1 to ${MAX_RUNNER_BYTES}`)
  }
  if (allowedHosts.size === 0 || !allowedHosts.has(normalizeHost(sourceUrl.hostname))) {
    throw new Error('DSH_RUNNER_ALLOWED_HOSTS must explicitly include the runner host')
  }

  return { sourceUrl, expectedSha256, expectedBytes, allowedHosts }
}

function request(url, allowedHosts, redirectsRemaining) {
  return new Promise((resolveRequest, rejectRequest) => {
    const requestHandle = get(url, {
      headers: { 'User-Agent': 'deepseek-harness-mobile-runner-import/1' },
      lookup: publicOnlyLookup,
      timeout: 30_000,
    }, response => {
      const status = response.statusCode ?? 0
      if ([301, 302, 303, 307, 308].includes(status)) {
        const location = response.headers.location
        response.resume()
        if (redirectsRemaining === 0 || location === undefined) {
          rejectRequest(new Error('Runner download exceeded the redirect limit'))
          return
        }
        const redirected = new URL(location, url)
        if (
          redirected.protocol !== 'https:'
          || redirected.username !== ''
          || redirected.password !== ''
          || isDisallowedHost(redirected.hostname)
          || !allowedHosts.has(normalizeHost(redirected.hostname))
        ) {
          rejectRequest(new Error('Runner download redirected to a disallowed origin'))
          return
        }
        request(redirected, allowedHosts, redirectsRemaining - 1).then(resolveRequest, rejectRequest)
        return
      }
      if (status < 200 || status > 299) {
        response.resume()
        rejectRequest(new Error(`Runner download failed with HTTP ${status}`))
        return
      }
      resolveRequest(response)
    })
    requestHandle.on('timeout', () => requestHandle.destroy(new Error('Runner download timed out')))
    requestHandle.on('error', rejectRequest)
  })
}

export async function verifyArm64Elf(filePath, expectedBytes, expectedSha256) {
  const fileStat = await stat(filePath)
  if (fileStat.size !== expectedBytes) throw new Error('Runner byte length does not match the pinned value')

  const file = await open(filePath, 'r')
  try {
    const header = Buffer.alloc(64)
    const { bytesRead } = await file.read(header, 0, header.length, 0)
    if (bytesRead < header.length) throw new Error('Runner is too small to be a valid ELF file')
    const hasElfMagic = header[0] === 0x7f && header.subarray(1, 4).toString('ascii') === 'ELF'
    const is64BitLittleEndian = header[4] === 2 && header[5] === 1
    const elfType = header.readUInt16LE(16)
    const machine = header.readUInt16LE(18)
    if (!hasElfMagic || !is64BitLittleEndian || ![2, 3].includes(elfType) || machine !== 183) {
      throw new Error('Runner is not an executable 64-bit little-endian ARM64 ELF file')
    }
  } finally {
    await file.close()
  }

  const digest = createHash('sha256')
  await new Promise((resolveHash, rejectHash) => {
    const input = createReadStream(filePath)
    input.on('data', chunk => digest.update(chunk))
    input.on('end', resolveHash)
    input.on('error', rejectHash)
  })
  if (digest.digest('hex') !== expectedSha256) throw new Error('Runner SHA-256 does not match the pinned value')
}

export async function prepareRunner(environment = process.env) {
  const config = parseRunnerConfig(environment)
  await mkdir(dirname(OUTPUT_PATH), { recursive: true })
  const temporaryPath = `${OUTPUT_PATH}.${process.pid}.tmp`

  try {
    const response = await request(config.sourceUrl, config.allowedHosts, MAX_REDIRECTS)
    const declaredLength = Number(response.headers['content-length'])
    if (Number.isFinite(declaredLength) && declaredLength !== config.expectedBytes) {
      response.resume()
      throw new Error('Runner Content-Length does not match the pinned byte length')
    }

    let receivedBytes = 0
    await new Promise((resolveWrite, rejectWrite) => {
      const output = createWriteStream(temporaryPath, { flags: 'wx', mode: 0o700 })
      response.on('data', chunk => {
        receivedBytes += chunk.length
        if (receivedBytes > config.expectedBytes || receivedBytes > MAX_RUNNER_BYTES) {
          response.destroy(new Error('Runner download exceeded the pinned byte limit'))
        }
      })
      response.on('error', rejectWrite)
      output.on('error', rejectWrite)
      output.on('finish', resolveWrite)
      response.pipe(output)
    })

    await verifyArm64Elf(temporaryPath, config.expectedBytes, config.expectedSha256)
    await chmod(temporaryPath, 0o700)
    await rename(temporaryPath, OUTPUT_PATH)
    process.stdout.write(`Verified ARM64 runner imported from ${config.sourceUrl.hostname}\n`)
  } finally {
    await rm(temporaryPath, { force: true })
  }
}

const invokedPath = process.argv[1] === undefined ? '' : resolve(process.argv[1])
if (fileURLToPath(import.meta.url) === invokedPath) {
  prepareRunner().catch(error => {
    process.stderr.write(`${error instanceof Error ? error.message : 'Runner import failed'}\n`)
    process.exitCode = 1
  })
}
