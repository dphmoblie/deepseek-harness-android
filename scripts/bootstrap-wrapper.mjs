#!/usr/bin/env node
import { spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const targetJar = resolve('android/gradle/wrapper/gradle-wrapper.jar')
const bootstrapDir = resolve('.wrapper-bootstrap')
const bootstrapJar = resolve(bootstrapDir, 'gradle/wrapper/gradle-wrapper.jar')

if (existsSync(targetJar)) {
  console.log('[bootstrap-wrapper] gradle-wrapper.jar already present in android project.')
  process.exit(0)
}

mkdirSync(dirname(targetJar), { recursive: true })

if (existsSync(bootstrapJar)) {
  console.log('[bootstrap-wrapper] Found pre-generated wrapper jar in .wrapper-bootstrap, copying...')
  copyFileSync(bootstrapJar, targetJar)
  console.log('[bootstrap-wrapper] Successfully installed gradle-wrapper.jar.')
  process.exit(0)
}

console.log('[bootstrap-wrapper] Generating gradle-wrapper.jar using system gradle in .wrapper-bootstrap...')
const isWindows = process.platform === 'win32'
const gen = spawnSync('gradle', ['wrapper', '--no-daemon'], {
  cwd: bootstrapDir,
  stdio: 'inherit',
  shell: isWindows,
})

if (gen.status === 0 && existsSync(bootstrapJar)) {
  copyFileSync(bootstrapJar, targetJar)
  console.log('[bootstrap-wrapper] Successfully generated and installed gradle-wrapper.jar.')
  process.exit(0)
}

console.error(`
[bootstrap-wrapper] 错误: 未能在本地生成或找到 gradle-wrapper.jar。
原因: 出于供应链安全考量，本仓库不在 Git 中提交二进制 jar。
解决方法 (任选其一):
  1. 安装 Gradle (8.11.1 或兼容版本):
     macOS: brew install gradle
     Linux: sudo apt install gradle 或 sdk install gradle 8.11.1
     Windows: winget install Gradle.Gradle 或 choco install gradle
     安装后重新运行此脚本: pnpm run bootstrap:wrapper
  2. 若 CI 之前生成过，可从 CI 归档或队友处获取 gradle-wrapper.jar
     并放入 android/gradle/wrapper/ 目录中。
`)
process.exit(1)
