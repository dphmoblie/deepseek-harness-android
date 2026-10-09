import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const root = '../android/app/src/main/'
const read = (path) => readFile(new URL(root + path, import.meta.url), 'utf8')
const base = 'java/io/deepseekharness/mobile/'
const shell = await read(base + 'virtualscreen/ShellVirtualScreen.kt')
const service = await read(base + 'virtualscreen/VirtualScreenService.kt')
const plugin = await read(base + 'MobileRuntimePlugin.kt')
const accessibility = await read(base + 'accessibility/DeepSeekAccessibilityService.kt')

// Native wiring checks complement JVM policy tests; device behavior still needs instrumentation.
test('frame state reads cached foreground; health reuses one activity dump even with follow off', () => {
  const state = shell.slice(shell.indexOf('@Synchronized fun state()'), shell.indexOf('@Synchronized fun action('))
  assert.match(state, /this\.virtualForeground/)
  assert.doesNotMatch(state, /command\(|foregroundComponent\(|resumeForeground\(/)
  const tick = shell.slice(shell.indexOf('private fun autoFollowTick('), shell.indexOf('private fun tickResult('))
  assert.equal((tick.match(/"activity", "activities"/g) ?? []).length, 1)
  assert.match(tick, /virtualForeground = VirtualScreenPolicy\.resumedComponent\(dump, id\(\)\)/)
  assert.ok(tick.indexOf('virtualForeground = VirtualScreenPolicy') < tick.indexOf('policy == VirtualScreenPolicy.AUTO_FOLLOW_OFF'))
  const health = service.slice(service.indexOf('private val health'), service.indexOf('fun state()'))
  assert.match(health, /canObserve\(\) && actionSlot\.tryAcquire\(\)/)
  assert.doesNotMatch(health, /optString\("autoFollow"/)
})

test('target changes keep restart component and package in sync, including URI and rollback', () => {
  for (const [packageValue, component] of [
    ["component.substringBefore('/')", 'component'],
    ["previous.substringBefore('/')", 'previous'],
    ["launched.substringBefore('/')", 'launched'],
    ['candidate', 'component'],
    ['applied.target', 'component'],
  ]) {
    const assignments = `targetPackage = ${packageValue} targetComponent = ${component}`
    assert.ok(shell.replace(/\s+/g, ' ').includes(assignments))
  }
  assert.match(shell, /val previous = targetComponent\.takeIf/)
  assert.match(shell, /return virtualForeground\?\.takeIf/)
  assert.match(shell, /val candidate = foreground\?\.substringBefore\('\/'\)/)
})

test('stop bridge waits off main thread and release signal follows native close', () => {
  const stop = plugin.slice(plugin.indexOf('fun stopVirtualScreen('), plugin.indexOf('fun getVirtualScreenState('))
  assert.match(stop, /execute\(call\)/)
  assert.match(stop, /service\.requestStop\(\)\.get\(30, TimeUnit\.SECONDS\)/)
  assert.match(stop, /if \(!released\) throw RuntimeFailure\("VIRTUAL_SCREEN_CLOSE_FAILED"/)
  const destroy = service.slice(service.indexOf('override fun onDestroy()'), service.indexOf('companion object {'))
  assert.ok(destroy.indexOf('closeVirtualScreen(session)') < destroy.indexOf('current = null'))
  assert.ok(destroy.indexOf('current = null') < destroy.indexOf('stopped.complete(released)'))
})

test('accessibility launch checks resolved recipient and pins that component before starting', () => {
  const launch = accessibility.slice(accessibility.indexOf('override fun launch('), accessibility.indexOf('private fun dispatch('))
  assert.match(launch, /ComponentName\.unflattenFromString\(component\)/)
  assert.ok(launch.indexOf('!uri.isNullOrEmpty()') < launch.indexOf('!component.isNullOrEmpty()'))
  assert.ok(launch.indexOf('intent.resolveActivity') < launch.indexOf('AutomationLaunchPolicy.allowed(resolved.packageName'))
  assert.ok(launch.indexOf('AutomationLaunchPolicy.allowed(resolved.packageName') < launch.indexOf('intent.component = resolved'))
  assert.ok(launch.indexOf('intent.component = resolved') < launch.indexOf('service.startActivity(intent)'))
})

test('gesture capability is declared and long click prefers the node action', async () => {
  assert.match(await read('res/xml/accessibility_service_config.xml'), /android:canPerformGestures="true"/)
  const press = accessibility.slice(accessibility.indexOf('override fun longClickNode('), accessibility.indexOf('override fun pressBack('))
  assert.ok(press.indexOf('ACTION_LONG_CLICK') < press.indexOf('GestureDescription.Builder()'))
})

test('auto-disable persistence is package-scoped and example files are not scan-exempt', async () => {
  const disable = accessibility.slice(accessibility.indexOf('private fun disableAutomationRule('), accessibility.indexOf('private fun loadAutomationRules('))
  assert.match(disable, /readPackage\(store, packageName\)/)
  assert.doesNotMatch(disable, /AutomationRuleStore\.packages/)
  const config = await readFile(new URL('../.gitleaks.toml', import.meta.url), 'utf8')
  assert.match(config, /useDefault = true/)
  assert.doesNotMatch(config, /allowlists?|paths\s*=/)
  const example = await readFile(new URL('../.env.example', import.meta.url), 'utf8')
  for (const line of example.split(/\r?\n/).filter((line) => /_API_KEY=/.test(line))) {
    assert.match(line, /_API_KEY=REPLACE_WITH_YOUR_[A-Z_]+$/)
  }
})
