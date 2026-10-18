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

function bashPath(path) {
  return path.replace(/^([A-Za-z]):[\\/]/u, (_, drive) => `/${drive.toLowerCase()}/`).replaceAll('\\', '/')
}

function sourceStep(workflow) {
  const match = workflow.match(
    /^      - name: Package Ubuntu git corresponding source\n        run: \|\n([\s\S]*?)(?=^      - name: |$(?![\s\S]))/mu,
  )
  assert.ok(match, 'Ubuntu git source step is required')
  return match[1].split('\n').map(line => line.startsWith('          ') ? line.slice(10) : line).join('\n')
}

const mocks = String.raw`
apt-get() {
  printf '%s\n' "$*" >> "$DSH_APT_LOG"
  case "$*" in
    *' source --download-only git')
      if test "x$DSH_APT_SCENARIO" = xfail-source; then
        return 42
      fi
      if test "x$DSH_APT_SCENARIO" = xmismatch; then
        printf 'Source: git\nVersion: 2.54.0-1\n' > "$DSH_SOURCE_OUTPUT/git_2.55.0-0ppa1.dsc"
      else
        printf 'Source: git\nVersion: 1:2.55.0-0ppa1~ubuntu24.04.2\n' > "$DSH_SOURCE_OUTPUT/git_2.55.0-0ppa1.dsc"
      fi
      printf 'source archive\n' > "$DSH_SOURCE_OUTPUT/git_2.55.0.orig.tar.xz"
      printf 'debian archive\n' > "$DSH_SOURCE_OUTPUT/git_2.55.0.debian.tar.xz"
      ;;
  esac
}
sudo() { "$@"; }
dpkg-query() {
  case "$*" in
    *source:Package*)
      if test "x$DSH_APT_SCENARIO" = xmissing-version; then
        printf 'git\t\n'
      else
        printf 'git\t1:2.55.0-0ppa1~ubuntu24.04.2\n'
      fi
      ;;
    *) printf 'git\t1:2.55.0-0ppa1~ubuntu24.04.2\tarm64\n' ;;
  esac
}
`

async function runSourceStep(t, scenario = 'success') {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-git-source-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const aptRoot = join(dir, 'apt')
  const sourceParts = join(aptRoot, 'sources.list.d')
  await mkdir(sourceParts, { recursive: true })
  await writeFile(join(aptRoot, 'sources.list'), '# main source\ndeb [arch=arm64] http://archive.ubuntu.com/ubuntu noble main\n')
  await writeFile(
    join(sourceParts, 'git-core.list'),
    'deb [arch=amd64 signed-by=/usr/share/keyrings/git-core.gpg] https://ppa.launchpadcontent.net/git-core/ppa/ubuntu noble main\n',
  )
  await writeFile(
    join(sourceParts, 'ubuntu.sources'),
    'Types:   deb\nURIs: http://archive.ubuntu.com/ubuntu\nSuites: noble\nComponents: main\nArchitectures: amd64\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n',
  )
  const packages = join(dir, 'network-tools-packages.txt')
  const log = join(dir, 'apt.log')
  const archive = join(dir, 'ubuntu-git-corresponding-source.tar.gz')
  await writeFile(packages, 'git\n')
  await writeFile(log, '')
  const workflow = (await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')).replaceAll('\r\n', '\n')
  const script = sourceStep(workflow)
  const result = spawnSync(bash, ['--noprofile', '--norc'], {
    cwd: dir,
    env: {
      ...process.env,
      DSH_APT_HOST_ROOT: bashPath(aptRoot),
      DSH_APT_WORK_ROOT: bashPath(dir),
      DSH_NETWORK_PACKAGES_FILE: bashPath(packages),
      DSH_GIT_SOURCE_ARCHIVE: bashPath(archive),
      DSH_SOURCE_OUTPUT: bashPath(join(dir, 'git-source/files')),
      DSH_APT_LOG: bashPath(log),
      DSH_APT_SCENARIO: scenario,
    },
    input: `${mocks}\n${script}`,
    encoding: 'utf8',
    timeout: 30_000,
    windowsHide: true,
  })
  return { dir, archive, log, sourceParts: join(dir, 'ubuntu-sourceparts'), result }
}

test('enables deb-src in legacy and deb822 sources while requesting the exact installed git source', async t => {
  const { archive, log, sourceParts, result } = await runSourceStep(t)
  assert.ifError(result.error)
  assert.equal(result.status, 0, result.stderr)
  const legacy = await readFile(join(sourceParts, 'ubuntu-2.list'), 'utf8')
  const deb822 = await readFile(join(sourceParts, 'ubuntu-3.sources'), 'utf8')
  assert.match(legacy, /^deb-src \[signed-by=\/usr\/share\/keyrings\/git-core\.gpg\]/mu)
  assert.doesNotMatch(legacy, /arch=/u)
  assert.match(deb822, /^Types:\s+deb deb-src$/mu)
  assert.match(deb822, /Signed-By: \/usr\/share\/keyrings\/ubuntu-archive-keyring\.gpg/u)
  assert.doesNotMatch(deb822, /Architectures:/u)
  const aptLog = await readFile(log, 'utf8')
  assert.match(aptLog, /update -qq/u)
  assert.match(aptLog, /APT::Architecture=amd64/u)
  assert.match(aptLog, /source --download-only git\n/u)
  assert.doesNotMatch(aptLog, /source --download-only git=/u)
  assert.ok((await readFile(archive)).length > 0)
})

test('fails closed when the exact installed source version is unavailable', async t => {
  const { archive, log, result } = await runSourceStep(t, 'fail-source')
  assert.ifError(result.error)
  const aptLog = await readFile(log, 'utf8')
  assert.notEqual(result.status, 0, `${result.stdout}${result.stderr}\n${aptLog}`)
  assert.match(aptLog, /source --download-only git\n/u)
  assert.doesNotMatch(aptLog, /source --download-only git=/u)
  await assert.rejects(readFile(archive), { code: 'ENOENT' })
})

test('fails closed when dpkg has no source version metadata', async t => {
  const { archive, log, result } = await runSourceStep(t, 'missing-version')
  assert.ifError(result.error)
  const aptLog = await readFile(log, 'utf8')
  assert.notEqual(result.status, 0, `${result.stdout}${result.stderr}\n${aptLog}`)
  assert.match(aptLog, /update -qq/u)
  assert.doesNotMatch(aptLog, /source --download-only/u)
  await assert.rejects(readFile(archive), { code: 'ENOENT' })
})

test('fails closed when the downloaded dsc version does not match the installed binary', async t => {
  const { archive, log, result } = await runSourceStep(t, 'mismatch')
  assert.ifError(result.error)
  assert.notEqual(result.status, 0, `${result.stdout}${result.stderr}`)
  assert.match(await readFile(log, 'utf8'), /source --download-only git\n/u)
  await assert.rejects(readFile(archive), { code: 'ENOENT' })
})
