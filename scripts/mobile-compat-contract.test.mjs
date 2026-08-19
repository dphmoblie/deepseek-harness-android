import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'

const appRoot = resolve(import.meta.dirname, '..')

test('dsh-mobile-compat keeps the dsh client module contract', async () => {
  const packageJson = JSON.parse(
    await readFile(resolve(appRoot, 'packages/dsh-mobile-compat/package.json'), 'utf8'),
  )
  assert.deepEqual(packageJson.exports?.['./client'], {
    types: './lib/client.d.ts',
    default: './lib/client.js',
  })
  assert.deepEqual(packageJson.dsh?.client, {
    inject: [
      '@deepseek-ai/dsh-client-runtime',
      '@deepseek-ai/dsh-client-ui-theme',
    ],
    platform: 'web',
  })

  // The workspace build is a TypeScript module used by the package toolchain.
  // The default Android profile deliberately does not load this experimental
  // package; the official layout owns the root slot in production.
  const clientSource = await readFile(
    resolve(appRoot, 'packages/dsh-mobile-compat/lib/client.js'),
    'utf8',
  )
  assert.match(clientSource, /export const inject\s*=\s*\['slots'\]/)
  assert.match(clientSource, /export function apply\(ctx\)/)
})

test('Android rootfs workflow preserves the official frontend dist', async () => {
  const workflow = await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')
  assert.doesNotMatch(workflow, /rebuild-rootfs-frontend\.py/)
  assert.doesNotMatch(workflow, /--dist\s+harness-web\/dist/)
  assert.match(workflow, /official @deepseek-ai\/dsh-web-frontend\/dist/)
})

test('Android rootfs workflow tolerates node-pty version drift without hiding failures', async () => {
  const workflow = await readFile(resolve(appRoot, '.github/workflows/android-build.yml'), 'utf8')
  assert.doesNotMatch(workflow, /node-pty@1\.1\.0/)
  assert.match(workflow, /mapfile -t PTP_DIRS/)
  assert.match(workflow, /expected exactly one node-pty package/)
  assert.match(workflow, /test -s "\$PTP\/prebuilds\/linux-arm64\/pty\.node"/)
  assert.doesNotMatch(workflow, /tee \/tmp\/(?:step|bundle|release)\.log \|\| true/)
  assert.doesNotMatch(workflow, /PIPESTATUS/)
  assert.equal(workflow.match(/STATUS="\$\?"/g)?.length, 3)
})

test('the default mobile profile keeps the official layout as the sole root owner', async () => {
  const profile = JSON.parse(
    await readFile(resolve(appRoot, 'scripts/mobile-profile.example.json'), 'utf8'),
  )
  const bundles = profile?.dsh?.profile?.bundles
  assert.ok(Array.isArray(bundles))
  assert.ok(bundles.includes('@deepseek-ai/dsh-web-app'))
  assert.ok(!bundles.includes('dsh-mobile-compat'))
  assert.equal(profile?.mobile?.layout, undefined)
  assert.equal(profile?.mobile?.disabledOnMobile, undefined)
})

test('runtime packaging patches the official client error display at the UI boundary', async () => {
  const builder = await readFile(resolve(appRoot, 'scripts/build-embedded-runtime.py'), 'utf8')
  assert.match(builder, /def patch_client_failure_display\(dsh_root: Path\)/)
  assert.match(builder, /Failure details unavailable/)
  assert.match(builder, /const placeholders = new Set/)
  assert.match(builder, /dsh-client-failure-display-v2/)
  assert.match(builder, /Array\.isArray\(value\)/)
  assert.match(builder, /unique_file_candidates\(candidates\)/)
  assert.match(builder, /patch_client_failure_display\(args\.dsh_root\)/)
})

test('runtime packaging keeps official settings usable in a narrow WebView', async () => {
  const builder = await readFile(resolve(appRoot, 'scripts/build-embedded-runtime.py'), 'utf8')
  assert.match(builder, /def patch_client_mobile_settings_layout\(dsh_root: Path\)/)
  assert.match(builder, /dsh-mobile-settings-layout-v1/)
  assert.match(builder, /@media \(max-width:600px\)/)
  assert.match(builder, /patch_client_mobile_settings_layout\(args\.dsh_root\)/)
})

test('runtime packaging exposes official Tool details without replacing the root layout', async () => {
  const builder = await readFile(resolve(appRoot, 'scripts/build-embedded-runtime.py'), 'utf8')
  assert.match(builder, /def patch_client_tool_details_action\(dsh_root: Path\)/)
  assert.match(builder, /dsh-client-tool-details-action-v1/)
  assert.match(builder, /data-dsh-open-tool-details/)
  assert.match(builder, /IconInspectOutline12/)
  assert.match(builder, /def patch_client_tool_details_entry\(dsh_root: Path\)/)
  assert.match(builder, /dsh-client-tool-details-entry-v2/)
  assert.match(builder, /callId\.length > 256/)
  assert.match(builder, /target\.closest\("\[data-dsh-open-tool-details\]"\)/)
  assert.match(builder, /onClick: openToolDetails/)
  assert.match(builder, /and "onKeyDown: openToolDetails" not in text/)
  assert.match(builder, /def patch_client_mobile_tool_details_layout\(dsh_root: Path\)/)
  assert.match(builder, /dsh-mobile-tool-details-layout-v1/)
  assert.match(builder, /data-dsh-details-column/)
  assert.match(builder, /patch_client_tool_details_action\(args\.dsh_root\)/)
  assert.match(builder, /patch_client_tool_details_entry\(args\.dsh_root\)/)
  assert.match(builder, /patch_client_mobile_tool_details_layout\(args\.dsh_root\)/)
})

test('bundle verification keeps the official profile baseline without a mobile manifest', async () => {
  const verifier = await readFile(resolve(appRoot, 'scripts/verify-bundle.py'), 'utf8')
  assert.match(verifier, /PROFILE_BUNDLE_NAMES\s*=\s*\(/)
  assert.match(verifier, /profile_bundle_names\s*=\s*list\(PROFILE_BUNDLE_NAMES\)/)
  assert.match(verifier, /runtime contains disabled optional bundle/)
  assert.match(verifier, /manifest mobile profile enables a disabled Android bundle/)
  assert.match(verifier, /runtime contains build-only package-manager metadata/)
})
