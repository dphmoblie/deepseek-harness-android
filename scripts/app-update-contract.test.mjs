import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { test } from 'node:test'

const root = resolve(import.meta.dirname, '..')
const plugin = (await readFile(resolve(root, 'android/app/src/main/java/io/deepseekharness/mobile/MobileRuntimePlugin.kt'), 'utf8')).replace(/\r\n/gu, '\n')

function method(name) {
  const start = plugin.indexOf(`    fun ${name}(call: PluginCall)`)
  assert.ok(start >= 0, `缺少桥接方法 ${name}`)
  const end = plugin.indexOf('\n    @PluginMethod', start)
  assert.ok(end > start, `找不到 ${name} 的后续方法边界`)
  return plugin.slice(start, end)
}

test('所有应用更新操作共用进程级更新锁，不占运行时审计锁', () => {
  const companion = plugin.slice(plugin.indexOf('    companion object {'), plugin.indexOf('    override fun load('))
  assert.match(companion, /private val appUpdateOperationLock = ReentrantLock\(\)/u)
  for (const name of ['getAppUpdateState', 'setAppUpdateChannel', 'downloadAppUpdate', 'installAppUpdate', 'openAppUpdateInstallSettings']) {
    assert.match(method(name), /audited\(AuditEvent\.APP_UPDATE, appUpdateOperationLock\)/u, name)
  }
  assert.match(method('startHarness'), /audited\(AuditEvent\.RUNTIME_START\)/u)
  assert.match(plugin, /private fun <T> audited\(event: AuditEvent, lock: ReentrantLock = auditedOperationLock, operation: \(\) -> T\): T \{\s*return lock\.withLock/u)
})

test('频道检查及结果构建成功后才保存频道', () => {
  const change = method('setAppUpdateChannel')
  const check = change.indexOf('val state = AppUpdateCache.from(context).state(')
  const converted = change.indexOf(').toJsObject()', check)
  const save = change.indexOf('AppUpdateChannel.save(context, channel)')
  assert.ok(check >= 0 && converted > check && save > converted)
  assert.match(change.slice(check, save), /forceRefresh = true/u)
  assert.match(change.slice(save), /AppUpdateChannel\.save\(context, channel\)\s*state/u)
})

test('安装必须核对当前发布，并只读取对应版本的已下载包', () => {
  const install = method('installAppUpdate')
  assert.match(install, /val release = requireAppUpdate\(\)/u)
  assert.match(install, /AppUpdateInstallPolicy\.requireInstallable\(\s*AppReleaseCatalog\.installAllowed\(context\),\s*AppUpdateStorage\.downloaded\(context\.cacheDir, release\.version\),\s*release,/u)
  assert.ok(install.indexOf('requireAppUpdate()') < install.indexOf('AppUpdateInstallPolicy.requireInstallable('))
})
