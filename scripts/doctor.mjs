#!/usr/bin/env node
import { spawnSync } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'

console.log('=== DeepSeek Harness Mobile: Environment Doctor ===\n')

let hasWarnings = false
let hasErrors = false

function ok(message) {
  console.log(`\x1b[32m[OK]\x1b[0m ${message}`)
}

function warn(message, remedy = '') {
  hasWarnings = true
  console.log(`\x1b[33m[WARN]\x1b[0m ${message}`)
  if (remedy) console.log(`       -> 建议: ${remedy}`)
}

function fail(message, remedy = '') {
  hasErrors = true
  console.log(`\x1b[31m[FAIL]\x1b[0m ${message}`)
  if (remedy) console.log(`       -> 必须解决: ${remedy}`)
}

// 1. Node.js
// 口径必须和 package.json 的 engines（^22.19.0 || >=24.0.0）一致：只看大版本会让 22.0–22.18
// 也打印 [OK]，而真正跑 `pnpm install` 时又会被 EBADENGINE 拦下——诊断工具不能给这种假绿灯。
const nodeVersion = process.version
const [nodeMajor, nodeMinor] = nodeVersion.replace(/^v/, '').split('.').map(part => parseInt(part, 10))
const nodeOk = nodeMajor > 24 || nodeMajor === 24 || (nodeMajor === 22 && nodeMinor >= 19)
if (nodeOk) {
  ok(`Node.js version: ${nodeVersion}`)
} else {
  fail(`Node.js version ${nodeVersion} is below required ^22.19.0 || >=24.0.0`, '请升级 Node.js')
}

// 2. pnpm
const pnpmCheck = spawnSync('pnpm', ['--version'], { encoding: 'utf-8', shell: process.platform === 'win32' })
if (pnpmCheck.status === 0) {
  ok(`pnpm version: ${pnpmCheck.stdout.trim()}`)
} else {
  fail('pnpm is not found in PATH', '请安装 pnpm: npm i -g pnpm@11.19.0')
}

// 3. Java
const javaCheck = spawnSync('java', ['-version'], { encoding: 'utf-8', shell: process.platform === 'win32' })
if (javaCheck.status === 0) {
  const versionLine = (javaCheck.stderr || javaCheck.stdout).split('\n')[0]
  ok(`Java available: ${versionLine.trim()}`)
} else {
  fail('Java runtime is not found', '请安装 JDK 21+ 并配置 JAVA_HOME')
}

// 4. Android SDK & NDK
const sdkRoot = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT ||
  (process.platform === 'win32' ? resolve(process.env.LOCALAPPDATA || '', 'Android/Sdk') : resolve(process.env.HOME || '', 'Android/Sdk'))

if (existsSync(sdkRoot)) {
  ok(`Android SDK located: ${sdkRoot}`)

  // Check NDK 27.1.12297006
  const ndkPath = resolve(sdkRoot, 'ndk/27.1.12297006')
  if (existsSync(ndkPath)) {
    ok('Android NDK pinned version 27.1.12297006 is installed')
  } else {
    warn('Android NDK 27.1.12297006 未找到', '可通过 sdkmanager "ndk;27.1.12297006" 或 Android Studio SDK Manager 安装')
  }

  // Check platforms/android-35
  const platform35 = resolve(sdkRoot, 'platforms/android-35')
  if (existsSync(platform35)) {
    ok('Android Platform API 35 is installed')
  } else {
    warn('Android Platform 35 未找到', '请通过 sdkmanager "platforms;android-35" 安装')
  }
} else {
  warn(`Android SDK 路径未检测到 (检测路径: ${sdkRoot})`, '请配置 ANDROID_HOME 环境变量')
}

// 5. Gradle Wrapper
const wrapperJar = resolve('android/gradle/wrapper/gradle-wrapper.jar')
if (existsSync(wrapperJar)) {
  ok('Gradle Wrapper JAR is installed')
} else {
  fail('android/gradle/wrapper/gradle-wrapper.jar 缺失', '请运行: pnpm run bootstrap:wrapper')
}

// 6. Keystore
const keystoreProps = resolve('android/keystore.properties')
if (existsSync(keystoreProps)) {
  ok('android/keystore.properties is configured (Team signing active)')
} else {
  warn('android/keystore.properties 未配置', '本地已支持纯 Debug 模式自动回退，若需发布 Release 请向团队索取密钥')
}

// 7. Submodule
const harnessWebDir = resolve('harness-web')
if (existsSync(harnessWebDir) && readdirSync(harnessWebDir).length > 0) {
  ok('Submodule harness-web is initialized')
} else {
  fail('Submodule harness-web 未拉取', '请运行: git submodule update --init --recursive')
}

// 8. PRoot runners
const prootPath = resolve('android/app/src/main/jniLibs/arm64-v8a/libdsh_proot.so')
if (existsSync(prootPath)) {
  ok('PRoot native runner is present')
} else {
  warn('PRoot 运行器 (libdsh_proot.so) 缺失', '如需在真机运行容器环境，请参考 android/README.md 准备或从 CI 下载')
}

console.log('\n--- 诊断结果 ---')
if (hasErrors) {
  console.log('\x1b[31m存在必须解决的阻断问题，请参考上方提示处理。\x1b[0m')
  process.exit(1)
} else if (hasWarnings) {
  console.log('\x1b[33m存在警告项（不影响普通前端/Debug本地开发，但发布或完整功能可能受限）。\x1b[0m')
} else {
  console.log('\x1b[32m所有编译环境检查项均已就绪！\x1b[0m')
}
