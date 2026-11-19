import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const appRoot = resolve(import.meta.dirname, '..')
const bash = process.platform === 'win32'
  ? [process.env.ProgramFiles, 'C:/Program Files']
    .filter(Boolean).map(root => join(root, 'Git/bin/bash.exe')).find(existsSync) ?? 'bash'
  : 'bash'

function bashPath(path) {
  return path.replace(/^([A-Za-z]):[\\/]/u, (_, drive) => `/${drive.toLowerCase()}/`).replaceAll('\\', '/')
}

test('Android CI workflow keeps concurrency, early parallel checks and release-safe caches', async () => {
  const workflow = (await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')).replaceAll('\r\n', '\n')

  assert.match(workflow, /^concurrency:\n  #[^\n]*\n  #[^\n]*\n  group: \$\{\{ github\.workflow \}\}-\$\{\{ github\.event\.pull_request\.number \|\| github\.ref \}\}\n  cancel-in-progress: \$\{\{ github\.ref != 'refs\/heads\/main' && github\.event_name != 'workflow_dispatch' \}\}$/mu)
  assert.match(workflow, /^\s{2}checks:\n[\s\S]*?\n\s{2}build:\n\s{4}needs: \[checks, build-runners, build-rootfs\]/mu)
  assert.match(workflow, /^\s{2}publish-release:\n[\s\S]*?\n\s{4}needs: \[checks, build, build-rootfs, gates\]/mu)

  // PR/push 复用确定的 PRoot 产物缓存；手动发布强制现编。
  assert.match(workflow, /if: github\.event_name != 'workflow_dispatch'[\s\S]{0,300}hashFiles\('scripts\/ci-build-proot-runners\.sh'\)/u)
  assert.match(workflow, /key: rootfs-inputs-\$\{\{ runner\.os \}\}-\$\{\{ runner\.arch \}\}-04207713-01443c1e-d3b6805d/u)
  assert.match(workflow, /key: android-sdk-\$\{\{ runner\.os \}\}-p35-bt35\.0\.0-cmake3\.22\.1-ndk27\.1\.12297006/u)
  assert.doesNotMatch(workflow, /\n          echo "=== bundle step output ==="\n          cat \/tmp\/bundle\.log/u)
})

async function setupFixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-proot-runners-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const work = join(dir, 'work')
  const cache = join(dir, 'cache')
  const tmpWork = join(dir, 'tmp')
  const operitToolDir = join(work, 'operit2/tools/android-runtime')
  const operitJniDir = join(work, 'operit2/apps/flutter/app/android/app/src/main/jniLibs/arm64-v8a')
  const eventsPath = join(dir, 'events.log')
  await mkdir(operitToolDir, { recursive: true })
  await mkdir(operitJniDir, { recursive: true })
  await mkdir(tmpWork, { recursive: true })
  await mkdir(join(dir, 'sample-src'), { recursive: true })
  await writeFile(join(dir, 'sample-src/readme.txt'), 'ok\n')
  await writeFile(eventsPath, '')
  await writeFile(
    join(operitToolDir, 'build_android_tools_wsl.sh'),
    [
      '#!/usr/bin/env bash',
      'set -euo pipefail',
      'printf "build:%s:%s:%s\\n" "$ANDROID_NDK_HOME" "$OPERIT_ANDROID_RUNTIME_ABIS" "$OPERIT_ANDROID_RUNTIME_COMPONENTS" >> "$DSH_RUNNER_EVENTS"',
      'mkdir -p apps/flutter/app/android/app/src/main/jniLibs/arm64-v8a',
      'printf "proot-bin\\n" > apps/flutter/app/android/app/src/main/jniLibs/arm64-v8a/liboperit_proot.so',
      'printf "loader-bin\\n" > apps/flutter/app/android/app/src/main/jniLibs/arm64-v8a/liboperit_loader.so',
    ].join('\n'),
  )
  await chmod(join(operitToolDir, 'build_android_tools_wsl.sh'), 0o755)

  const archiveTar = join(dir, 'dummy.tar')
  const tarCreate = spawnSync(bash, ['--noprofile', '--norc', '-c', `tar -cf "${bashPath(archiveTar)}" -C "${bashPath(join(dir, 'sample-src'))}" readme.txt`], {
    encoding: 'utf8',
    windowsHide: true,
  })
  assert.equal(tarCreate.status, 0, tarCreate.stderr)

  const scriptSource = (await readFile(resolve(appRoot, 'scripts/ci-build-proot-runners.sh'), 'utf8')).replaceAll('\r\n', '\n')
  const env = {
    ...process.env,
    OPERIT2_REF: 'dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf',
    DSH_RUNNER_CACHE_DIR: bashPath(cache),
    DSH_RUNNER_TMP_DIR: bashPath(tmpWork),
    DSH_RUNNER_EVENTS: bashPath(eventsPath),
    DSH_DUMMY_TAR: bashPath(archiveTar),
    DSH_BAD_CHECKSUM: '0',
  }

  const preamble = [
    'curl() {',
    '  local out="" url=""',
    '  while [ "$#" -gt 0 ]; do',
    '    case "$1" in',
    '      -o) out="$2"; shift 2 ;;',
    '      http*) url="$1"; shift ;;',
    '      *) shift ;;',
    '    esac',
    '  done',
    '  printf \'curl:%s->%s\\n\' "$url" "$out" >> "$DSH_RUNNER_EVENTS"',
    '  if [ "$DSH_BAD_CHECKSUM" = 1 ]; then',
    '    printf \'corrupt\\n\' > "$out"',
    '  else',
    '    cp "$DSH_DUMMY_TAR" "$out"',
    '  fi',
    '}',
    'sha256sum() {',
    '  if [ "$1" = -c ] && [ "${2:-}" = - ]; then',
    '    local line expected target',
    '    read -r line',
    '    expected="${line%%  *}"',
    '    target="${line#*  }"',
    '    [ -f "$target" ] || return 1',
    '    if grep -q \'^corrupt$\' "$target" 2>/dev/null; then',
    '      printf \'%s: FAILED\\n\' "$target" >&2',
    '      return 1',
    '    fi',
    '    printf \'%s: OK\\n\' "$target"',
    '    return 0',
    '  fi',
    '  command sha256sum "$@"',
    '}',
    'tar() {',
    '  if [ "$1" = -xf ]; then',
    '    return 0',
    '  fi',
    '  command tar "$@"',
    '}',
    'unzip() {',
    '  local dest=""',
    '  while [ "$#" -gt 0 ]; do',
    '    case "$1" in',
    '      -d) dest="$2"; shift 2 ;;',
    '      *) shift ;;',
    '    esac',
    '  done',
    '  printf \'unzip:%s\\n\' "$dest" >> "$DSH_RUNNER_EVENTS"',
    '  mkdir -p "$dest/android-ndk-r29-beta4/toolchains/llvm/prebuilt/linux-x86_64/bin"',
    '  printf \'#!/bin/sh\\n\' > "$dest/android-ndk-r29-beta4/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"',
    '  chmod +x "$dest/android-ndk-r29-beta4/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"',
    '}',
    'git() {',
    '  if [ "${1:-}" = -C ] && [ "${3:-}" = archive ]; then',
    '    printf \'git-archive:%s\\n\' "$*" >> "$DSH_RUNNER_EVENTS"',
    '    cat "$DSH_DUMMY_TAR"',
    '    return 0',
    '  fi',
    '  command git "$@"',
    '}',
  ].join('\n')
  const run = (extraEnv = {}) => spawnSync(bash, ['--noprofile', '--norc'], {
    cwd: work,
    env: { ...env, ...extraEnv },
    encoding: 'utf8',
    timeout: 30_000,
    windowsHide: true,
    input: `${preamble}\n${scriptSource.replace(/^#!.*\n/u, '')}`,
  })
  return { work, cache, eventsPath, run }
}

test('ci-build-proot-runners.sh downloads and builds on cache miss, then reuses verified cache on hit', async t => {
  const { work, eventsPath, run } = await setupFixture(t)

  const first = run()
  assert.equal(first.status, 0, first.stdout + first.stderr)
  assert.ok(existsSync(join(work, 'runners/libdsh_proot.so')))
  assert.ok(existsSync(join(work, 'runners/libdsh_proot_loader.so')))
  assert.ok(existsSync(join(work, 'proot-corresponding-source.tar.gz')))

  const firstEvents = await readFile(eventsPath, 'utf8')
  assert.match(firstEvents, /curl:https:\/\/dl\.google\.com\/android\/repository\/android-ndk-r29-beta4-linux\.zip/u)
  assert.match(firstEvents, /unzip:/u)
  assert.match(firstEvents, /build:.*android-ndk-r29-beta4:arm64-v8a:proot/u)

  await writeFile(eventsPath, '')
  const second = run()
  assert.equal(second.status, 0, second.stdout + second.stderr)
  const secondEvents = await readFile(eventsPath, 'utf8')
  assert.doesNotMatch(secondEvents, /^curl:/mu, 'valid cached sources and NDK must skip downloads')
  assert.doesNotMatch(secondEvents, /^unzip:/mu, 'valid cached NDK must skip unzip')
  assert.match(secondEvents, /build:.*android-ndk-r29-beta4:arm64-v8a:proot/u)
})

test('ci-build-proot-runners.sh fails closed when downloaded archive fails checksum', async t => {
  const { work, run } = await setupFixture(t)
  const failed = run({ DSH_BAD_CHECKSUM: '1' })
  assert.notEqual(failed.status, 0)
  assert.equal(existsSync(join(work, 'runners/libdsh_proot.so')), false)
})
