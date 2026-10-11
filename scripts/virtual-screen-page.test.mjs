import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const source = await readFile(new URL('../android/app/src/main/java/io/deepseekharness/mobile/virtualscreen/VirtualScreenActivity.kt', import.meta.url), 'utf8')

// Android view wiring; device layout and touch behavior still require a device check.
test('frame rate controls scroll at touchable widths outside the preview', () => {
  assert.match(source, /modes = HorizontalScrollView\(this\)/)
  assert.match(source, /LinearLayout\.LayoutParams\(dp\(80\), dp\(48\)\)/)
  assert.match(source, /root\.addView\(modes/)
  assert.doesNotMatch(source, /content\.addView\(modes/)
})

test('pending controls cannot be re-enabled by periodic state refresh', () => {
  const send = source.slice(source.indexOf('private fun sendAction('), source.indexOf('private fun updateControls('))
  assert.ok(send.indexOf('if (actionPending || !resumed) return') < send.indexOf('worker.execute'))
  assert.ok(send.indexOf('actionPending = true') < send.indexOf('worker.execute'))
  assert.ok(send.indexOf('actionPending = false') > send.indexOf('service.action(request)'))
  assert.match(source, /back\.isEnabled = running && !actionPending/)
  assert.match(source, /button\.isEnabled = running && !actionPending && mode != active/)
  const refresh = source.slice(source.indexOf('private val refresh ='), source.indexOf('private fun pageSpec('))
  assert.match(refresh, /updateControls\(snapshot\)/)
  assert.doesNotMatch(refresh, /\.isEnabled =/)
})

test('preview survives a transient pause such as a permission dialog', () => {
  const pause = source.slice(source.indexOf('override fun onPause()'), source.indexOf('override fun onDestroy()'))
  assert.match(pause, /if \(isFinishing\)/u)
  assert.doesNotMatch(pause, /preview\?\.let \{ content\.removeView\(it\) \}; preview = null\s*super/u)
})
