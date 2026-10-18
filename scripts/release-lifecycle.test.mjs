import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
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

// Execute the real workflow shell steps in order; only network/build tools are mocked.
// In particular, command failures must exercise Bash's real set -e / pipefail behavior.
function releaseSteps(workflow) {
  const job = workflow.split('  publish-release:\n')[1]?.split('  gates:\n')[0]
  assert.ok(job, 'publish-release job is required')
  return [...job.matchAll(/^      - name: (.+)\n([\s\S]*?)(?=^      - |$(?![\s\S]))/gmu)]
    .flatMap(([, name, body]) => {
      const script = body.split('        run: |\n')[1]
      if (!script) return []
      return [{
        name,
        onFailure: /^        if: failure\(\)$/mu.test(body),
        script: script.split('\n').map(line => line.replace(/^          /u, '')).join('\n')
          .replace(/\$\{\{ github\.repository \}\}/gu, 'example/release-fixture')
          .replace(/\$\{\{ github\.event\.inputs\.release_type \}\}/gu, 'stable')
          .replace(/\$\{\{ github\.run_number \}\}/gu, '1')
          .replaceAll('/tmp/', '${DSH_RELEASE_TEST_TMP}/'),
      }]
    })
}

const mocks = String.raw`
git() {
  if [ "$1" = log ]; then printf '%s\n' '- fixture change'; fi
}
jq() {
  local last
  for last; do :; done
  if [ "$1" = -r ]; then
    local response
    read -r response
    if [[ "$response" = *'"id":123'* ]]; then printf '123\n'; else printf 'null\n'; fi
  elif [[ "$last" = *'draft:true'* ]]; then
    printf '{"draft":true}\n'
  else
    printf '{"draft":false}\n'
  fi
}
curl() {
  local method='' body='' url='' arg
  while [ "$#" -gt 0 ]; do
    arg="$1"; shift
    case "$arg" in
      -X) method="$1"; shift ;;
      -d) body="$1"; shift ;;
      -H|--data-binary) shift ;;
      https://*) url="$arg" ;;
    esac
  done
  if [[ "$url" = */releases && "$method" = POST ]]; then
    printf 'create:%s\n' "$body" >> "$DSH_RELEASE_EVENTS"
    if [ "$DSH_RELEASE_SCENARIO" = existing ]; then return 22; fi
    printf 'true\n' > "$DSH_RELEASE_STATE"
    if [ "$DSH_RELEASE_SCENARIO" = malformed ]; then printf '{"id":null}\n'; else printf '{"id":123}\n'; fi
  elif [[ "$url" = */releases/123/assets?name=* && "$method" = POST ]]; then
    # The ID/tag must survive even failure of the first upload, not just the last one.
    grep -qx 'RELEASE_ID=123' "$GITHUB_ENV" || return 90
    grep -qx 'RELEASE_TAG=v0.0.0' "$GITHUB_ENV" || return 91
    [ "$(<"$DSH_RELEASE_STATE")" = true ] || return 92
    printf 'upload:%s\n' "$(printf '%s' "$url" | cut -d= -f2)" >> "$DSH_RELEASE_EVENTS"
    if [ "$DSH_RELEASE_SCENARIO" = upload ]; then return 22; fi
    printf '{}\n'
  elif [[ "$url" = */releases/123 && "$method" = PATCH ]]; then
    printf 'patch:%s\n' "$body" >> "$DSH_RELEASE_EVENTS"
    if [ "$body" = '{"draft":false}' ]; then
      printf 'false\n' > "$DSH_RELEASE_STATE"
      if [ "$DSH_RELEASE_SCENARIO" = publish ]; then return 22; fi
    else
      printf 'true\n' > "$DSH_RELEASE_STATE"
    fi
    printf '{}\n'
  else
    printf 'unexpected-request:%s:%s\n' "$method" "$url" >> "$DSH_RELEASE_EVENTS"
    return 93
  fi
}
gh() { printf 'download\n' >> "$DSH_RELEASE_EVENTS"; }
python3() {
  if [[ "$1" = *release-manifest.py ]]; then
    shift
    while [ "$#" -gt 0 ]; do
      if [ "$1" = --out ]; then printf '{}\n' > "$2"; return; fi
      shift
    done
    return 94
  fi
  if [[ "$1" = *create-corresponding-source-manifest.py ]]; then
    shift
    while [ "$#" -gt 0 ]; do
      if [ "$1" = --out ]; then
        shift; printf '{"schemaVersion":1,"assets":[]}' > "$1"; return 0
      fi
      shift
    done
    return 95
  fi
  if [[ "$1" = *verify-corresponding-source.py ]]; then
    return 0
  fi
  printf 'verify\n' >> "$DSH_RELEASE_EVENTS"
  if [ "$DSH_RELEASE_SCENARIO" = verify ]; then return 1; fi
}
`

async function runLifecycle(t, scenario) {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-release-lifecycle-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  // Git Bash parses the workflow's POSIX paths. Keep the fixture path in that
  // form too; a raw Windows `C:\\...` path is read by tar as a remote target.
  const bashDir = dir.replace(/^([A-Za-z]):[\\/]/u, (_, drive) => `/${drive.toLowerCase()}/`).split('\\').join('/')
  for (const folder of ['apk', 'runtime', 'proot-source', 'git-source']) await mkdir(join(dir, folder))
  for (const file of [
    'apk/app-release.apk', 'runtime/rootfs.bundle', 'runtime/runtime-manifest.json',
    'proot-source/proot-corresponding-source.tar.gz',
    'git-source/ubuntu-git-corresponding-source.tar.gz',
  ]) {
    await writeFile(join(dir, file), 'fixture')
  }
  const eventsPath = join(dir, 'events')
  const statePath = join(dir, 'state')
  const envPath = join(dir, 'github-env')
  await writeFile(eventsPath, '')
  await writeFile(envPath, '')
  const env = {
    ...process.env,
    APP_VERSION: '0.0.0', GH_TOKEN: '', GITHUB_SHA: 'a'.repeat(40),
    GITHUB_WORKSPACE: dir.replaceAll('\\', '/'), GITHUB_ENV: envPath.replaceAll('\\', '/'),
    TMPDIR: bashDir,
    DSH_RELEASE_TEST_TMP: bashDir,
    DSH_RELEASE_EVENTS: eventsPath.replaceAll('\\', '/'),
    DSH_RELEASE_STATE: statePath.replaceAll('\\', '/'),
    DSH_RELEASE_SCENARIO: scenario,
  }
  const workflow = (await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')).replaceAll('\r\n', '\n')
  const steps = releaseSteps(workflow)
  assert.equal(steps.length, 4, 'execute create/upload, verify, publish, failure cleanup')
  let failed = false
  const executed = []
  for (const step of steps) {
    if (step.onFailure !== failed) continue
    const result = spawnSync(bash, ['--noprofile', '--norc'], {
      cwd: appRoot, env, encoding: 'utf8', timeout: 30_000, windowsHide: true,
      input: `${mocks}\n${step.script}`,
    })
    assert.ifError(result.error)
    executed.push({ name: step.name, status: result.status, output: result.stdout + result.stderr })
    failed ||= result.status !== 0
    for (const line of (await readFile(envPath, 'utf8')).trim().split('\n').filter(Boolean)) {
      const split = line.indexOf('=')
      env[line.slice(0, split)] = line.slice(split + 1)
    }
  }
  return {
    failed, executed,
    events: (await readFile(eventsPath, 'utf8')).trim().split('\n').filter(Boolean),
    draft: existsSync(statePath) ? (await readFile(statePath, 'utf8')).trim() : undefined,
  }
}

test('release is public only after every draft upload, download and verification succeed', async t => {
  const result = await runLifecycle(t, 'success')
  assert.equal(result.failed, false, JSON.stringify(result.executed))
  assert.deepEqual(result.events, [
    'create:{"draft":true}',
    'upload:app-release.apk', 'upload:rootfs.bundle', 'upload:runtime-manifest.json',
    'upload:corresponding-source.tar.gz',
    'upload:release-manifest.json', 'upload:checksums.txt',
    'download', 'verify', 'patch:{"draft":false}',
  ])
  assert.equal(result.draft, 'false')
})

test('interrupted first upload preserves a draft with its persisted ID and never verifies or publishes', async t => {
  const result = await runLifecycle(t, 'upload')
  assert.equal(result.failed, true)
  assert.deepEqual(result.events, ['create:{"draft":true}', 'upload:app-release.apk', 'patch:{"draft":true}'])
  assert.equal(result.draft, 'true')
})

test('verification failure cannot expose the release', async t => {
  const result = await runLifecycle(t, 'verify')
  assert.equal(result.failed, true)
  assert.deepEqual(result.events.slice(-3), ['download', 'verify', 'patch:{"draft":true}'])
  assert.equal(result.events.includes('patch:{"draft":false}'), false)
  assert.equal(result.draft, 'true')
})

test('ambiguous publish response is followed by re-drafting only the newly created release', async t => {
  const result = await runLifecycle(t, 'publish')
  assert.equal(result.failed, true)
  assert.deepEqual(result.events.slice(-2), ['patch:{"draft":false}', 'patch:{"draft":true}'])
  assert.equal(result.draft, 'true')
})

for (const scenario of ['existing', 'malformed']) {
  test(`creation failure (${scenario}) never looks up or patches another release`, async t => {
    const result = await runLifecycle(t, scenario)
    assert.equal(result.failed, true)
    assert.deepEqual(result.events, ['create:{"draft":true}'])
  })
}
