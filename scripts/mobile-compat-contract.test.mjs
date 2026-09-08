import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'

const appRoot = resolve(import.meta.dirname, '..')

test('Android rootfs workflow packages the adapted official frontend at the root', async () => {
  const workflow = await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')
  assert.match(workflow, /Build Android-adapted official Harness frontend/)
  assert.match(workflow, /pnpm --dir harness-web build/)
  assert.match(workflow, /rebuild-rootfs-frontend\.py/)
  assert.match(workflow, /--dist\s+harness-web\/dist/)

  const rebuilder = await readFile(resolve(appRoot, 'scripts/rebuild-rootfs-frontend.py'), 'utf8')
  assert.match(rebuilder, /is_frontend_dist_path\(member\.name\)/)
  assert.match(rebuilder, /重建后仍残留旧 dist 条目/)
  assert.match(rebuilder, /OFFICIAL_FRONTEND_MARKER/)
  assert.match(rebuilder, /validate_frontend_dist\(dist_root\)/)
  assert.match(rebuilder, /name="dsh-official-frontend" content="android-adapted-v1"/)
})

test('official frontend adapter keeps upstream assets without a second conversation entry', async () => {
  const packageJson = JSON.parse(await readFile(resolve(appRoot, 'harness-web/package.json'), 'utf8'))
  const adapter = await readFile(
    resolve(appRoot, 'harness-web/scripts/build-official-frontend.mjs'),
    'utf8',
  )
  assert.equal(packageJson.scripts.build, 'node scripts/build-official-frontend.mjs')
  assert.equal(packageJson.scripts.test, 'node --test scripts/*.test.mjs')
  assert.match(adapter, /cp\(sourceRoot, temporaryRoot/)
  assert.match(adapter, /dsh-official-frontend/)
  assert.match(adapter, /dsh-android\.css/)
  assert.doesNotMatch(adapter, /plugin-workbench-loader/)
  assert.doesNotMatch(adapter, /createRoot\(|import\(['"]\.\/mobile/)
  await assert.rejects(readFile(resolve(appRoot, 'harness-web/src/main.tsx')), { code: 'ENOENT' })
  await assert.rejects(readFile(resolve(appRoot, 'harness-web/scripts/embed-plugin-workbench.mjs')), { code: 'ENOENT' })
})

test('rootfs frontend input rejects old workbench artifacts and duplicate HTML entries', () => {
  const probe = String.raw`
import importlib.util
import pathlib
import tempfile

spec = importlib.util.spec_from_file_location("frontend_rebuilder", "scripts/rebuild-rootfs-frontend.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
marker = b'<meta name="dsh-official-frontend" content="android-adapted-v1" /><div id="root"></div>'

def rejected(root):
    try:
        module.validate_frontend_dist(root)
    except module.BuildError:
        return
    raise AssertionError("invalid frontend distribution was accepted")

with tempfile.TemporaryDirectory() as temporary:
    root = pathlib.Path(temporary)
    index = root / "index.html"
    index.write_bytes(marker)
    module.validate_frontend_dist(root)
    for relative in ("plugin-workbench-loader.js", "plugin-workbench/assets/entry.js", "other/index.html"):
        legacy = root / relative
        legacy.parent.mkdir(parents=True, exist_ok=True)
        legacy.write_bytes(b"stale")
        rejected(root)
        legacy.unlink()
    index.write_bytes(marker + b'<meta name="dsh-mobile-frontend" content="harness-web-v1" />')
    rejected(root)
    index.write_bytes(b"<div id=root></div>")
    rejected(root)
`
  const result = spawnSync(process.platform === 'win32' ? 'python' : 'python3', ['-c', probe], {
    cwd: appRoot,
    encoding: 'utf8',
    timeout: 10_000,
    windowsHide: true,
    env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
  })
  assert.ifError(result.error)
  assert.equal(result.status, 0, result.stderr)
})

test('Android rootfs workflow tolerates node-pty version drift without hiding failures', async () => {
  const workflow = await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')
  assert.doesNotMatch(workflow, /node-pty@1\.1\.0/)
  assert.match(workflow, /mapfile -t PTP_DIRS/)
  assert.match(workflow, /expected exactly one node-pty package/)
  assert.match(workflow, /test -s "\$PTP\/prebuilds\/linux-arm64\/pty\.node"/)
  assert.doesNotMatch(workflow, /tee \/tmp\/(?:step|bundle|release)\.log \|\| true/)
  assert.doesNotMatch(workflow, /PIPESTATUS/)
  assert.doesNotMatch(workflow, /git add -f/)
  assert.doesNotMatch(workflow, /git checkout --orphan/)
  assert.doesNotMatch(workflow, /ci-logs/)
  assert.equal(workflow.match(/STATUS="\$\?"/g)?.length, 3)
})

test('Android CI installs the runtime from a committed frozen lockfile', async () => {
  const workflow = await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')
  const runtimePackage = JSON.parse(
    await readFile(resolve(appRoot, 'scripts/runtime-profile/package.json'), 'utf8'),
  )
  const runtimeLock = await readFile(
    resolve(appRoot, 'scripts/runtime-profile/pnpm-lock.yaml'),
    'utf8',
  )
  assert.match(workflow, /cp scripts\/runtime-profile\/package\.json scripts\/runtime-profile\/pnpm-lock\.yaml/)
  assert.match(workflow, /pnpm install --frozen-lockfile/)
  assert.doesNotMatch(workflow, /pnpm install --no-frozen-lockfile/)
  assert.match(runtimeLock, /lockfileVersion: '9\.0'/)
  for (const version of [
    ...Object.values(runtimePackage.dependencies ?? {}),
    ...Object.values(runtimePackage.devDependencies ?? {}),
  ]) {
    assert.match(version, /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/)
  }
})

test('stable releases are main-only and bind the release to the built commit', async () => {
  const workflow = await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')
  assert.match(workflow, /^permissions:\s*\r?\n\s+contents: read$/m)
  assert.match(workflow, /github\.event\.inputs\.release_type != 'stable' \|\| github\.ref == 'refs\/heads\/main'/)
  assert.match(workflow, /permissions:\s*\r?\n\s+contents: write\s*\r?\n\s+# \u63a8\u9001\u5206\u652f/m)
  assert.match(workflow, /target_commitish:\$sha/)
})

test('the default mobile profile avoids a second root-layout plugin', async () => {
  const profile = JSON.parse(
    await readFile(resolve(appRoot, 'scripts/mobile-profile.example.json'), 'utf8'),
  )
  const bundles = profile?.dsh?.profile?.bundles
  assert.ok(Array.isArray(bundles))
  assert.ok(bundles.includes('@deepseek-ai/dsh-web-app'))
  assert.equal(profile?.mobile?.layout, undefined)
  assert.equal(profile?.mobile?.disabledOnMobile, undefined)
})

test('runtime packaging leaves the version-matched official client immutable', async () => {
  const builder = await readFile(resolve(appRoot, 'scripts/build-embedded-runtime.py'), 'utf8')
  assert.doesNotMatch(builder, /patch_client_failure_display\(args\.dsh_root\)/)
  assert.doesNotMatch(builder, /patch_client_mobile_settings_layout\(args\.dsh_root\)/)
  assert.doesNotMatch(builder, /patch_client_tool_details_action\(args\.dsh_root\)/)
})

test('bundle verification keeps the official profile baseline without a mobile manifest', async () => {
  const verifier = await readFile(resolve(appRoot, 'scripts/verify-bundle.py'), 'utf8')
  assert.match(verifier, /PROFILE_BUNDLE_NAMES\s*=\s*\(/)
  assert.match(verifier, /profile_bundle_names\s*=\s*list\(PROFILE_BUNDLE_NAMES\)/)
  assert.match(verifier, /runtime contains build-only package-manager metadata/)
  assert.match(verifier, /OFFICIAL_FRONTEND_MARKER/)
  assert.match(verifier, /expected exactly one official frontend index/)
  assert.match(verifier, /legacy custom frontend artifact remains/)
  assert.match(verifier, /legacy mobile frontend marker remains/)
  assert.match(verifier, /official frontend index missing #root/)
  assert.match(verifier, /duplicate frontend entry/)
})
