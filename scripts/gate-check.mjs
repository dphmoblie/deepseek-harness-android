#!/usr/bin/env node
import { spawnSync } from 'node:child_process'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

console.log('=== DeepSeek Harness Mobile: Quality Gate Check ===\n')
let hasError = false

function runStep(index, name, cmd, args) {
  process.stdout.write(`[${index}/4] Running ${name}... `)
  const res = spawnSync(cmd, args, { stdio: 'pipe', encoding: 'utf-8' })
  if (res.status === 0) {
    console.log('PASS')
  } else {
    console.log('FAIL')
    if (res.stdout) console.log(res.stdout)
    if (res.stderr) console.error(res.stderr)
    hasError = true
  }
}

// 1. Validate manifests against schemas
runStep(1, 'Manifest schema validation', 'node', ['scripts/validate-manifests.mjs'])

// 2. Android resources check
const pyCmd = process.platform === 'win32' ? 'python' : 'python3'
runStep(2, 'Android resources integrity', pyCmd, ['scripts/check-android-resources.py'])

// 3. Submodule pin check
process.stdout.write('[3/4] Checking harness-web pin consistency... ')
try {
  const pinPath = resolve('harness-web.pin')
  if (existsSync(pinPath)) {
    const pinSha = readFileSync(pinPath, 'utf8')
      .split('\n')
      .map(line => line.trim())
      .filter(line => line && !line.startsWith('#'))[0]

    const gitSubmodule = spawnSync('git', ['-C', 'harness-web', 'rev-parse', 'HEAD'], {
      encoding: 'utf-8',
    })
    const actualSha = gitSubmodule.stdout ? gitSubmodule.stdout.trim() : ''

    // 这里以前是 `pinSha && actualSha && pinSha !== actualSha` 才算失败：pin 文件为空、
    // 或子模块没拉取（rev-parse 无输出）时两边都取不到值，于是打印 PASS。子模块版本对不上
    // 是真实的发布风险，两个值都读不到是「没检查」而不是「检查通过」，必须分开报。
    if (!pinSha) {
      console.log('FAIL')
      console.error('harness-web.pin 存在，但里面没有可用的 pin 值')
      hasError = true
    } else if (!actualSha) {
      console.log('FAIL')
      console.error('取不到 harness-web 的 HEAD：子模块可能没初始化（git submodule update --init --recursive）')
      hasError = true
    } else if (pinSha !== actualSha) {
      console.log('FAIL')
      console.error(`Submodule mismatch: harness-web.pin=${pinSha}, submodule HEAD=${actualSha}`)
      hasError = true
    } else {
      console.log('PASS')
    }
  } else {
    console.log('SKIP (no pin file)')
  }
} catch (e) {
  console.log('FAIL')
  console.error(`子模块 pin 检查无法完成：${e.message}`)
  hasError = true
}

// 4. Git diff whitespace check
// CI 的 gates 作业对「本次推送区间」跑 `git diff --check`，命中就以非零退出；本地这条必须以
// 同样的口径失败，否则本地绿灯推上去照样红，门禁就变成了安慰剂。本地只能看工作区到 HEAD 的
// 区间，这一点在输出里说明，避免误以为和 CI 完全等价。
process.stdout.write('[4/4] Checking whitespace and conflict markers... ')
const diffCheck = spawnSync('git', ['diff', '--check', 'HEAD'], {
  encoding: 'utf-8',
})
if (diffCheck.status === 0) {
  console.log('PASS')
} else {
  console.log('FAIL (工作区存在行尾空白 / 冲突标记 / 多余空行；CI 对推送区间同样硬失败)')
  if (diffCheck.stdout) console.log(diffCheck.stdout)
  hasError = true
}

console.log('')
if (hasError) {
  console.error('Gate check failed! Please address the errors above before pushing.')
  process.exit(1)
} else {
  console.log('All gate checks passed!')
}
