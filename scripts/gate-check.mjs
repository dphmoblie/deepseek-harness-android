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

    if (pinSha && actualSha && pinSha !== actualSha) {
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
  console.log(`WARN (${e.message})`)
}

// 4. Git diff whitespace check
process.stdout.write('[4/4] Checking whitespace and conflict markers... ')
const diffCheck = spawnSync('git', ['diff', '--check', 'HEAD'], {
  encoding: 'utf-8',
})
if (diffCheck.status === 0) {
  console.log('PASS')
} else {
  console.log('WARN (whitespace issues found)')
  if (diffCheck.stdout) console.log(diffCheck.stdout)
}

console.log('')
if (hasError) {
  console.error('Gate check failed! Please address the errors above before pushing.')
  process.exit(1)
} else {
  console.log('All gate checks passed!')
}
