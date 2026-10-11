import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const service = await readFile(new URL('../android/app/src/main/java/io/deepseekharness/mobile/virtualscreen/VirtualScreenService.kt', import.meta.url), 'utf8')
const shell = await readFile(new URL('../android/app/src/main/java/io/deepseekharness/mobile/virtualscreen/ShellVirtualScreen.kt', import.meta.url), 'utf8')
const tick = service.slice(service.indexOf('private val tick = object : Runnable'))
const fetch = tick.slice(tick.indexOf('worker.execute {'), tick.indexOf('main.post {'))

test('hardware Bitmaps remain decoded without CPU samples or a CPU blank flag', () => {
  assert.match(fetch, /fetchedHardware = hardware[\s\S]*Bitmap\.wrapHardwareBuffer\(/u)
  assert.match(tick, /val decoded = bitmap != null/u)
  assert.match(tick, /val hasContent = if \(hardwareFrame\) decoded/u)
  assert.match(tick, /val outcome = VirtualScreenPolicy\.previewOutcome\(alive, decoded, !hasContent, changed\)/u)
  assert.doesNotMatch(tick, /optBoolean\("frameBlank"/u)
  assert.doesNotMatch(tick, /previewOutcome\(alive, samples != null/u)
})

test('frame fetching never closes or replaces the currently displayed hardware buffer', () => {
  assert.doesNotMatch(fetch, /displayedHardware/u)
  assert.match(fetch, /catch \(_: Exception\) \{\s*fetchedHardware\?\.close\(\)\s*fetchedHardware = null/u)
  assert.match(tick, /if \(version != lifecycle\.generation \|\| executor == null\) \{\s*fetchedHardware\?\.close\(\)\s*return@post/u)
})

test('preview cadence subtracts fetch and presentation time without scheduling overlapping fetches', () => {
  assert.ok(tick.indexOf('val startedAt = SystemClock.elapsedRealtime()') < tick.indexOf('worker.execute {'))
  assert.match(tick, /main\.postDelayed\(this, VirtualScreenPolicy\.previewPullDelay\(mode, SystemClock\.elapsedRealtime\(\) - startedAt\)\)/u)
  assert.equal((tick.match(/main\.postDelayed\(this,/gu) ?? []).length, 1)
})

test('static pages can be captured from the retained image without waiting for another frame', () => {
  const collect = shell.slice(shell.indexOf('private fun collectFrame('), shell.indexOf('private fun measuredFps('))
  assert.match(collect, /latestImage\?\.close\(\)\s*latestImage = image\s*retained = true/u)
  assert.match(collect, /finally \{ if \(!retained\) image\.close\(\)/u)
  assert.doesNotMatch(collect, /copyPixelsFromBuffer|getPixels|blankFrame\(/u)
  const snapshot = shell.slice(shell.indexOf('private fun awaitFrame('), shell.indexOf('private fun copyFrame('))
  assert.match(snapshot, /while \(reader === source && latestImage == null\)/u)
  assert.match(snapshot, /if \(reader !== source\) throw RuntimeFailure\("VIRTUAL_SCREEN_STOPPED"/u)
  assert.match(snapshot, /encodedFrame\?\.let \{ return@synchronized it \}/u)
  assert.match(snapshot, /val current = copyFrame\(image\)/u)
  assert.doesNotMatch(shell, /cpuCaptureRequested|cpuFrameAt/u)
  const close = shell.slice(shell.indexOf('@Synchronized fun close()'))
  assert.match(close, /latestImage\?\.close\(\); latestImage = null/u)
  assert.match(close, /notifyAll\(\)/u)
})

test('UI frame replacement transfers ownership while rejected and cleared frames release their buffers', () => {
  const show = tick.slice(tick.indexOf('PreviewFrameAction.SHOW ->'), tick.indexOf('PreviewFrameAction.KEEP ->'))
  const keep = tick.slice(tick.indexOf('PreviewFrameAction.KEEP ->'), tick.indexOf('PreviewFrameAction.CLEAR ->'))
  const clear = tick.slice(tick.indexOf('PreviewFrameAction.CLEAR ->'), tick.indexOf('// 拉取间隔'))
  assert.match(show, /val oldHardware = displayedHardware\s*displayedHardware = fetchedHardware\s*fetchedHardware = null\s*setImageBitmap\(it\); displayed = it\s*oldHardware\?\.close\(\)/u)
  assert.doesNotMatch(show, /displayedHardware\?\.close\(\)/u)
  assert.match(keep, /fetchedHardware\?\.close\(\)\s*fetchedHardware = null/u)
  assert.match(clear, /fetchedHardware\?\.close\(\)\s*fetchedHardware = null\s*clearFrame\(\)/u)
  assert.match(service, /private fun clearFrame\(\) \{[\s\S]*?setImageDrawable\(null\); displayed = null[\s\S]*?displayedHardware\?\.close\(\); displayedHardware = null/u)
})
