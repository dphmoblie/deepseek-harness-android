import assert from 'node:assert/strict'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { test } from 'node:test'
import {
  buildSandboxOverlayPatch,
  detectBootOutputFailures,
  EXPECTED_AUTH_CHALLENGE,
  resolveNodePtyDir,
  verifyAuthenticatedLaunch,
} from './ci-dsh-runtime-smoke.mjs'

const appRoot = resolve(import.meta.dirname, '..')

test('鉴权冒烟跟随 DSH 的 303 并验证同源会话首页', async t => {
  let location = './'
  let homeStatus = 200
  const requests = []
  const server = createServer((request, response) => {
    requests.push({ url: request.url, cookie: request.headers.cookie, auth: request.headers.authorization })
    if (request.url === '/?token=fixture') {
      response.writeHead(303, { Location: location, 'Set-Cookie': 'dsh_session=fixture; Path=/; HttpOnly' })
    } else {
      response.writeHead(homeStatus)
    }
    response.end()
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())))
  const launchUrl = `http://127.0.0.1:${server.address().port}/?token=fixture`
  const authorization = 'Basic fixture'

  await verifyAuthenticatedLaunch(launchUrl, authorization)
  assert.deepEqual(requests, [
    { url: '/?token=fixture', cookie: undefined, auth: authorization },
    { url: '/', cookie: 'dsh_session=fixture', auth: authorization },
  ])

  homeStatus = 401
  await assert.rejects(verifyAuthenticatedLaunch(launchUrl, authorization), /status=401/u)
  location = 'http://127.0.0.1:1/'
  await assert.rejects(verifyAuthenticatedLaunch(launchUrl, authorization), /必须与启动页同源/u)
})

test('detectBootOutputFailures 命中 Issue #31 的 Cordis 注入错误、插件未激活、缺模块与补丁未命中告警', () => {
  const issue31Output = [
    'dsh: warning: 1 entry did not activate',
    'mobile-shizuku-tools (@deepseek-harness/dsh-mobile-shizuku): Error: cannot get property "workingDirectory" without inject',
    '    at bindService (file:///opt/dsh/plugins/dsh-mobile-shizuku/lib/pty-guard.js:335:12)',
    'dsh web: http://127.0.0.1:3080/?token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  ].join('\n')
  const hits = detectBootOutputFailures(issue31Output)
  assert.ok(hits.includes('plugin-not-activated'), `expected plugin-not-activated, got ${hits}`)
  assert.ok(hits.includes('cordis-missing-inject'), `expected cordis-missing-inject, got ${hits}`)

  assert.deepEqual(
    detectBootOutputFailures('dsh: warning: 2 entries did not activate\nfirst: pending\nsecond: pending'),
    ['plugin-not-activated'],
  )

  assert.deepEqual(
    detectBootOutputFailures('dsh: patch: entry "sandbox" not found'),
    ['patch-entry-missing'],
  )
  assert.deepEqual(
    detectBootOutputFailures('Error [ERR_MODULE_NOT_FOUND]: Cannot find package "@deepseek-ai/schemastery"'),
    ['module-not-found'],
  )
  assert.deepEqual(
    detectBootOutputFailures('error: --profile <name> is required'),
    ['cli-option-error'],
  )
  assert.deepEqual(
    detectBootOutputFailures('error: unknown option \'--patch\''),
    ['cli-option-error'],
  )
  assert.deepEqual(
    detectBootOutputFailures('dsh: skipping profile bundle "dshmarket": cannot resolve'),
    ['bundle-skipped'],
  )
  assert.deepEqual(
    detectBootOutputFailures('dsh web: http://127.0.0.1:3080/?token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n'),
    [],
  )
})

test('EXPECTED_AUTH_CHALLENGE 与 mobile-auth-preload.cjs 和原生 RuntimeSupervisor 保持一致', async () => {
  const preload = await readFile(resolve(appRoot, 'scripts/mobile-auth-preload.cjs'), 'utf8')
  const supervisor = await readFile(
    resolve(appRoot, 'android/app/src/main/java/io/deepseekharness/mobile/runtime/RuntimeSupervisor.kt'),
    'utf8',
  )
  assert.equal(EXPECTED_AUTH_CHALLENGE, 'Basic realm="DeepSeek Harness Mobile", charset="UTF-8"')
  assert.match(preload, /const REALM = 'DeepSeek Harness Mobile'/u)
  assert.match(preload, /'WWW-Authenticate': `Basic realm="\$\{REALM\}", charset="UTF-8"`/u)
  assert.match(supervisor, /const val REALM = "DeepSeek Harness Mobile"/u)
  assert.match(
    supervisor,
    /EXPECTED_AUTH_CHALLENGE = "Basic realm=\\"\$\{HarnessAccess\.REALM\}\\", charset=\\"UTF-8\\""/u,
  )
})

test('buildSandboxOverlayPatch 与原生 RuntimeSandboxRunner 的插件 id 和签名保持一致', async () => {
  const kotlin = await readFile(
    resolve(appRoot, 'android/app/src/main/java/io/deepseekharness/mobile/runtime/RuntimeSandboxRunner.kt'),
    'utf8',
  )
  const patch = buildSandboxOverlayPatch()
  assert.equal(patch.length, 1)
  assert.equal(patch[0].id, 'sandbox')
  assert.match(kotlin, /const val PLUGIN_ID = "sandbox"/u)
  assert.deepEqual(patch[0].config.runnerCommand, ['/bin/sh', '/root/.dsh-mobile/sandbox-runner.sh'])
  assert.deepEqual(patch[0].config.runnerFailureSignatures, ['dsh-sandbox-runner: '])
  assert.match(kotlin, /const val FAILURE_SIGNATURE = "dsh-sandbox-runner: "/u)
})

test('Android CI 在 checks、build-rootfs 与 PR build 中接入真实 Cordis/dsh 冒烟与 lintRelease', async () => {
  const workflow = (await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')).replaceAll('\r\n', '\n')

  const checksJob = workflow.split('  checks:\n')[1]?.split('  build:\n')[0]
  assert.ok(checksJob, 'checks job must exist')
  assert.match(
    checksJob,
    /node scripts\/ci-dsh-runtime-smoke\.mjs --mode cordis-plugins --dsh-root scripts\/runtime-profile/u,
    'checks 作业必须在真实 Cordis 容器中冒烟验证内置插件',
  )
  assert.ok(
    checksJob.indexOf('Install Shizuku plugin contract dependencies') <
      checksJob.indexOf('ci-dsh-runtime-smoke.mjs --mode cordis-plugins'),
    'Cordis 冒烟必须排在安装 runtime-profile 依赖之后',
  )

  const buildJob = workflow.split('  build:\n')[1]?.split('  build-runners:\n')[0]
  assert.ok(buildJob, 'build job must exist')
  assert.match(
    buildJob,
    /\.\/gradlew assembleDebug lintDebug lintRelease testDebugUnitTest assembleDebugAndroidTest --no-daemon/u,
    'PR 构建必须同时执行 lintRelease 以拦截仅在 release 触发的 NewApi 等问题',
  )

  const rootfsJob = workflow.split('  build-rootfs:\n')[1]?.split('  publish-release:\n')[0]
  assert.ok(rootfsJob, 'build-rootfs job must exist')
  assert.match(
    rootfsJob,
    /node scripts\/ci-dsh-runtime-smoke\.mjs[\s\S]{0,160}--mode dsh-boot[\s\S]{0,200}--dsh-root \/tmp\/dsh-root[\s\S]{0,200}--verify-pty-native/u,
    'build-rootfs 必须在打包上传前执行真实 dsh web 启动、鉴权与 node-pty 加载冒烟',
  )
  assert.ok(
    rootfsJob.indexOf('--mode dsh-boot') < rootfsJob.indexOf('Upload runtime'),
    'dsh 启动冒烟必须拦在上传 embedded-runtime 之前',
  )
})

test('resolveNodePtyDir 在 pnpm 隔离布局下定位唯一 node-pty 并拒绝多副本', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-pty-resolve-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const single = join(dir, 'node_modules', '.pnpm', 'node-pty@1.2.0-beta.15', 'node_modules', 'node-pty')
  await mkdir(single, { recursive: true })
  await writeFile(join(single, 'package.json'), '{"name":"node-pty","version":"1.2.0-beta.15"}\n')
  assert.equal(await resolveNodePtyDir(dir), single)

  const duplicate = join(dir, 'node_modules', '.pnpm', 'node-pty@1.2.0-beta.16', 'node_modules', 'node-pty')
  await mkdir(duplicate, { recursive: true })
  await writeFile(join(duplicate, 'package.json'), '{"name":"node-pty","version":"1.2.0-beta.16"}\n')
  await assert.rejects(() => resolveNodePtyDir(dir), /发现多个 node-pty 物理副本/u)
})
