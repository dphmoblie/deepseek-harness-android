#!/usr/bin/env node
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'

const args = process.argv.slice(2)
const isWindows = process.platform === 'win32'
const androidDir = resolve('android')
const gradlewCmd = isWindows ? resolve(androidDir, 'gradlew.bat') : resolve(androidDir, 'gradlew')

if (!existsSync(gradlewCmd)) {
  console.error(`[run-gradle] Error: ${gradlewCmd} not found.`)
  process.exit(1)
}

const wrapperJar = resolve(androidDir, 'gradle/wrapper/gradle-wrapper.jar')
if (!existsSync(wrapperJar)) {
  console.warn('[run-gradle] Warning: gradle-wrapper.jar is missing. Attempting bootstrap...')
  const bootstrap = spawnSync(process.execPath, [resolve('scripts/bootstrap-wrapper.mjs')], {
    stdio: 'inherit',
  })
  if (bootstrap.status !== 0) {
    console.error('[run-gradle] Failed to bootstrap gradle wrapper jar.')
    process.exit(1)
  }
}

const result = spawnSync(gradlewCmd, args, {
  cwd: androidDir,
  stdio: 'inherit',
  shell: isWindows,
})

process.exit(result.status ?? 1)
