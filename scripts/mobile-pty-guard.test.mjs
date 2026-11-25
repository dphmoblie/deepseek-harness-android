import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import {
  diagnosePtyStartupFailure,
  healWorkingDirectory,
  installPtyGuard,
  wrapTerminalBackend,
} from './runtime-profile/plugins/dsh-mobile-shizuku/lib/pty-guard.js'

function mockFs(entries) {
  return {
    async stat(target) {
      const entry = entries[target]
      if (!entry) {
        const err = new Error(`ENOENT: ${target}`)
        err.code = 'ENOENT'
        throw err
      }
      return {
        isDirectory: () => entry.type === 'dir',
      }
    },
    async access(target) {
      const entry = entries[target]
      if (!entry) {
        const err = new Error(`ENOENT: ${target}`)
        err.code = 'ENOENT'
        throw err
      }
      if (!entry.executable) {
        const err = new Error(`EACCES: ${target}`)
        err.code = 'EACCES'
        throw err
      }
    },
  }
}

test('healWorkingDirectory 在目录可进入时原样放行，无执行位时自动回落并提交切换', async () => {
  const fsOps = mockFs({
    '/root/ok': { type: 'dir', executable: true },
    '/root/locked': { type: 'dir', executable: false },
    '/root/origin': { type: 'dir', executable: false },
    '/root': { type: 'dir', executable: true },
  })
  const committed = []
  const workingDirectory = {
    defaultDirectory: '/root',
    async set(_agent, target) {
      committed.push(target)
      return target
    },
  }
  const agent = { session: { header: { cwd: '/root/origin' } } }

  const ok = await healWorkingDirectory(workingDirectory, async () => '/root/ok', agent, undefined, { fsOps })
  assert.equal(ok, '/root/ok')
  assert.deepEqual(committed, [])

  const healed = await healWorkingDirectory(workingDirectory, async () => '/root/locked', agent, undefined, { fsOps })
  assert.equal(healed, '/root')
  assert.deepEqual(committed, ['/root'])
})

test('diagnosePtyStartupFailure 准确区分 cwd 权限、沙箱 exit 125 与 shell 缺失', async () => {
  const cwdDenied = await diagnosePtyStartupFailure({
    cwd: '/root/locked',
    argv: ['/bin/bash', '--noprofile', '--norc', '-i'],
    exitCode: 1,
    signal: 0,
    output: '\u001b[31mchdir(2) failed.: Operation not permitted\r\n',
  }, {
    fsOps: mockFs({
      '/root/locked': { type: 'dir', executable: false },
      '/bin/bash': { type: 'file', executable: true },
    }),
  })
  assert.match(cwdDenied, /^PTY shell exited during startup \(/)
  assert.match(cwdDenied, /exit=1/)
  assert.match(cwdDenied, /cwd=no_exec_bit/)
  assert.match(cwdDenied, /chdir\(2\) failed\.: Operation not permitted/)
  assert.match(cwdDenied, /working_directory/)

  const sandboxRejected = await diagnosePtyStartupFailure({
    cwd: '/root/my project',
    argv: ['/bin/sh', '/root/.dsh-mobile/sandbox-runner.sh'],
    exitCode: 125,
    signal: 0,
    output: 'dsh-sandbox-runner: unsupported read-write path\r\n',
  }, {
    fsOps: mockFs({
      '/root/my project': { type: 'dir', executable: true },
      '/bin/sh': { type: 'file', executable: true },
    }),
  })
  assert.match(sandboxRejected, /exit=125/)
  assert.match(sandboxRejected, /dsh-sandbox-runner: unsupported read-write path/)
  assert.match(sandboxRejected, /沙箱运行器拒绝启动/)

  const missingBash = await diagnosePtyStartupFailure({
    cwd: '/root',
    argv: ['/bin/bash'],
    exitCode: 1,
    signal: 0,
    output: 'execvp(3) failed.: No such file or directory\r\n',
  }, {
    fsOps: mockFs({
      '/root': { type: 'dir', executable: true },
    }),
  })
  assert.match(missingBash, /shell=missing/)
  assert.match(missingBash, /Shell 可执行文件不可用/)
})

test('wrapTerminalBackend 启动失败附带诊断，启动成功后立即解绑输出监听', async () => {
  const fsOps = mockFs({
    '/root': { type: 'dir', executable: true },
    '/root/locked': { type: 'dir', executable: false },
    '/bin/bash': { type: 'file', executable: true },
  })

  let lastOutputEmitter = null
  let shouldFail = true
  const backend = {
    type: 'bash',
    async spawnTerminal(termSpec) {
      const output = new EventEmitter()
      lastOutputEmitter = output
      let resolveDone
      const done = new Promise(resolve => { resolveDone = resolve })
      setImmediate(() => {
        if (shouldFail) {
          output.emit('data', Buffer.from('chdir(2) failed.: Operation not permitted\r\n'))
          resolveDone({ exitCode: 1, signal: null })
        }
      })
      return { output, done, termSpec }
    },
    async spawn(spec) {
      await this.spawnTerminal({ cwd: spec.cwd, argv: ['/bin/bash', '-i'] })
      if (shouldFail) {
        await new Promise(resolve => setImmediate(resolve))
        throw new Error('PTY shell exited during startup')
      }
      return { ok: true }
    },
  }

  wrapTerminalBackend(backend, { fsOps, settleWaitMs: 100 })

  await assert.rejects(
    backend.spawn({ cwd: '/root/locked' }),
    error => {
      assert.match(error.message, /^PTY shell exited during startup \(/)
      assert.match(error.message, /exit=1/)
      assert.match(error.message, /cwd=no_exec_bit/)
      assert.match(error.message, /chdir\(2\) failed\.: Operation not permitted/)
      return true
    },
  )
  assert.equal(lastOutputEmitter.listenerCount('data'), 0)

  shouldFail = false
  const session = await backend.spawn({ cwd: '/root' })
  assert.deepEqual(session, { ok: true })
  assert.equal(lastOutputEmitter.listenerCount('data'), 0)
})

test('installPtyGuard 通过可选 inject 挂接延迟注册的 workingDirectory 与 terminal 后端', async () => {
  const fsOps = mockFs({
    '/root/bad': { type: 'dir', executable: false },
    '/root': { type: 'dir', executable: true },
    '/bin/bash': { type: 'file', executable: true },
  })
  const injections = new Map()
  const ctx = {
    inject(names, callback) {
      injections.set(names.join(','), callback)
    },
  }

  installPtyGuard(ctx, { fsOps, settleWaitMs: 50 })
  assert.equal(injections.has('workingDirectory'), true)
  assert.equal(injections.has('terminals'), true)

  const workingDirectory = {
    defaultDirectory: '/root',
    async ensure() { return '/root/bad' },
    async set(_agent, target) { return target },
  }
  injections.get('workingDirectory')({ workingDirectory })
  const healed = await workingDirectory.ensure({ session: { header: {} } })
  assert.equal(healed, '/root')

  const backends = new Map()
  const terminals = {
    backends,
    registerBackend(backend) {
      backends.set(backend.type, backend)
    },
  }
  injections.get('terminals')({ terminals })

  const lateBackend = {
    type: 'bash',
    async spawnTerminal() {
      const output = new EventEmitter()
      const done = Promise.resolve({ exitCode: 125, signal: null })
      setImmediate(() => output.emit('data', 'dsh-sandbox-runner: landlock launcher not found\n'))
      return { output, done }
    },
    async spawn(spec) {
      await this.spawnTerminal({ cwd: spec.cwd, argv: ['/bin/sh', '/root/.dsh-mobile/sandbox-runner.sh'] })
      await new Promise(resolve => setImmediate(resolve))
      throw new Error('PTY shell exited during startup')
    },
  }
  terminals.registerBackend(lateBackend)

  await assert.rejects(
    lateBackend.spawn({ cwd: '/root' }),
    /PTY shell exited during startup \(.*exit=125.*dsh-sandbox-runner: landlock launcher not found/u,
  )
})

test('installPtyGuard 在 Cordis v4 严格代理禁止读取未声明 inject 属性时不会抛错且能正常挂接', async () => {
  const fsOps = mockFs({
    '/root/bad': { type: 'dir', executable: false },
    '/root': { type: 'dir', executable: true },
  })
  const injections = new Map()
  const disposedFibers = []
  const allowed = new Set(['inject', 'effect', 'on', 'tools', 'systemPrompt', 'attachments', 'llm'])
  const strictCordisCtx = new Proxy({
    inject(names, callback) {
      injections.set(names.join(','), callback)
      return { dispose: () => disposedFibers.push(names.join(',')) }
    },
  }, {
    get(target, prop, receiver) {
      if (typeof prop === 'string' && !allowed.has(prop)) {
        throw new Error(`cannot get property "${prop}" without inject`)
      }
      return Reflect.get(target, prop, receiver)
    },
  })

  const disposeGuard = installPtyGuard(strictCordisCtx, { fsOps, settleWaitMs: 50 })
  assert.equal(injections.has('workingDirectory'), true)
  assert.equal(injections.has('terminals'), true)

  const workingDirectory = {
    defaultDirectory: '/root',
    async ensure() { return '/root/bad' },
    async set(_agent, target) { return target },
  }
  injections.get('workingDirectory')({ workingDirectory })
  const healed = await workingDirectory.ensure({ session: { header: {} } })
  assert.equal(healed, '/root')

  disposeGuard()
  assert.deepEqual(disposedFibers, ['workingDirectory', 'terminals'])
})
