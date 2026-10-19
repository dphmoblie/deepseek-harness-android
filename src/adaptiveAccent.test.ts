import { beforeEach, describe, expect, it } from 'vitest'
import {
  ACCENT_LIGHTNESS_RANGE,
  ACCENT_SATURATION_RANGE,
  ACCENT_SOURCE_KEY,
  ACCENT_SYNC_KEY,
  ADAPTIVE_ACCENT_KEY,
  MIN_CONTRAST,
  clampAccent,
  clearAccentSync,
  clearAdaptiveAccent,
  contrastRatio,
  deriveAccentFromPixels,
  deriveAdaptiveAccent,
  deriveSurface,
  ensureContrast,
  hexToRgb,
  pickOnColor,
  readAccentSource,
  readAccentSync,
  readAdaptiveAccent,
  relativeLuminance,
  rgbToHex,
  rgbToHsl,
  hslToHex,
  isAccentSource,
  isHexColor,
  saveAccentSource,
  saveAccentSync,
  saveAdaptiveAccent,
  validateSyncedTheme,
} from './adaptiveAccent'

/** 造一块像素：每个 `[r, g, b]` 重复 `repeat` 次，alpha 固定不透明。 */
function pixels(colors: [number, number, number][], repeat = 1): Uint8ClampedArray {
  const data = new Uint8ClampedArray(colors.length * repeat * 4)
  let offset = 0
  for (const [red, green, blue] of colors) {
    for (let index = 0; index < repeat; index += 1) {
      data[offset] = red
      data[offset + 1] = green
      data[offset + 2] = blue
      data[offset + 3] = 255
      offset += 4
    }
  }
  return data
}

beforeEach(() => {
  window.localStorage.clear()
})

describe('颜色工具', () => {
  it('#RRGGBB 与 rgb 互转，非法值抛错', () => {
    expect(hexToRgb('#315fcf')).toEqual([49, 95, 207])
    expect(rgbToHex(49, 95, 207)).toBe('#315fcf')
    expect(isHexColor('#315fcf')).toBe(true)
    expect(isHexColor('#315fc')).toBe(false)
    expect(isHexColor('rgb(1,2,3)')).toBe(false)
    expect(() => hexToRgb('blue')).toThrow('颜色必须是 #RRGGBB')
  })

  it('对比度按 WCAG 计算：黑白是 21，同色是 1', () => {
    expect(relativeLuminance('#ffffff')).toBeCloseTo(1, 5)
    expect(relativeLuminance('#000000')).toBeCloseTo(0, 5)
    expect(contrastRatio('#ffffff', '#000000')).toBeCloseTo(21, 3)
    expect(contrastRatio('#315fcf', '#315fcf')).toBeCloseTo(1, 5)
  })

  it('前景色取黑白里对比度更高的那个', () => {
    expect(pickOnColor('#f7f8fa')).toBe('#000000')
    expect(pickOnColor('#101820')).toBe('#ffffff')
  })

  it('clampAccent 把纯黑纯白收回区间，色相不变', () => {
    const white = clampAccent('#ffffff')
    const black = clampAccent('#000000')
    // 8 位量化：0.34 落成像素值后回读会差一点点，容差按 1/255 给。
    const tolerance = 1 / 200
    const [, whiteSaturation, whiteLightness] = rgbToHsl(white)
    const [, blackSaturation, blackLightness] = rgbToHsl(black)
    expect(whiteLightness).toBeLessThanOrEqual(ACCENT_LIGHTNESS_RANGE[1] + tolerance)
    expect(blackLightness).toBeGreaterThanOrEqual(ACCENT_LIGHTNESS_RANGE[0] - tolerance)
    // 纯灰没有色相，夹紧后饱和度仍被抬到区间下限附近，避免变成“灰按钮”。
    expect(whiteSaturation).toBeGreaterThanOrEqual(ACCENT_SATURATION_RANGE[0] - tolerance)
    expect(blackSaturation).toBeGreaterThanOrEqual(ACCENT_SATURATION_RANGE[0] - tolerance)
    const clamped = clampAccent('#7a2f8c')
    expect(rgbToHsl(clamped)[0]).toBeCloseTo(rgbToHsl('#7a2f8c')[0], 2)
    expect(hslToHex(0.5, 0.5, 0.5)).toBe('#40bfbf')
  })
})

describe('从像素里挑主色', () => {
  it('全透明或只有灰阶时返回 null', () => {
    expect(deriveAccentFromPixels(new Uint8ClampedArray(0))).toBeNull()
    const transparent = new Uint8ClampedArray([49, 95, 207, 0, 49, 95, 207, 0])
    expect(deriveAccentFromPixels(transparent)).toBeNull()
    expect(deriveAccentFromPixels(pixels([[10, 10, 10], [250, 250, 250], [128, 128, 128]]))).toBeNull()
  })

  it('取面积占优的色相，并夹进可用区间', () => {
    const blue = deriveAccentFromPixels(pixels([[49, 95, 207]], 500))!
    expect(rgbToHsl(blue)[0]).toBeCloseTo(rgbToHsl('#315fcf')[0], 2)
    const [, saturation, lightness] = rgbToHsl(blue)
    expect(saturation).toBeGreaterThanOrEqual(ACCENT_SATURATION_RANGE[0] - 1e-9)
    expect(lightness).toBeGreaterThanOrEqual(ACCENT_LIGHTNESS_RANGE[0] - 1e-9)
    expect(lightness).toBeLessThanOrEqual(ACCENT_LIGHTNESS_RANGE[1] + 1e-9)
    // 少数派颜色不该赢过大面积主色。
    const mixed = deriveAccentFromPixels(pixels([...Array.from({ length: 200 }, () => [49, 95, 207] as [number, number, number]), [220, 40, 40]]))!
    expect(rgbToHsl(mixed)[0]).toBeCloseTo(rgbToHsl('#315fcf')[0], 1)
  })

  it('饱和度更高的小色块能压过同面积的灰调', () => {
    const vivid = deriveAccentFromPixels(pixels([...Array.from({ length: 20 }, () => [120, 130, 140] as [number, number, number]), ...Array.from({ length: 20 }, () => [200, 30, 60] as [number, number, number])]))!
    expect(rgbToHsl(vivid)[0]).toBeCloseTo(rgbToHsl('#c81e3c')[0], 1)
  })
})

describe('对比度兜底与面板色', () => {
  it('ensureContrast 给出的前景与底色至少 4.5:1', () => {
    for (const seed of ['#315fcf', '#f4e9a2', '#7a2f8c', '#c81e3c']) {
      const result = ensureContrast(clampAccent(seed))!
      expect(result).not.toBeNull()
      expect(contrastRatio(result.accent, result.onAccent)).toBeGreaterThanOrEqual(MIN_CONTRAST)
      expect(['#000000', '#ffffff']).toContain(result.onAccent)
    }
  })

  it('面板色随明暗模式分家', () => {
    const dark = deriveSurface('#315fcf', 'dark')
    const light = deriveSurface('#315fcf', 'light')
    expect(relativeLuminance(dark.background)).toBeLessThan(0.1)
    expect(relativeLuminance(light.background)).toBeGreaterThan(0.8)
    expect(relativeLuminance(dark.surface)).toBeGreaterThan(relativeLuminance(dark.background))
    for (const value of [dark.background, dark.surface, light.background, light.surface]) expect(isHexColor(value)).toBe(true)
  })

  it('deriveAdaptiveAccent 串起整条链路，透明度被夹到 0..100', () => {
    const result = deriveAdaptiveAccent(pixels([[49, 95, 207]], 64), { theme: 'dark', source: 'video', surfaceOpacity: 186, now: 1700000000000 })!
    expect(result.source).toBe('video')
    expect(result.extractedAt).toBe(1700000000000)
    expect(result.surfaceOpacity).toBe(100)
    expect(isHexColor(result.accent)).toBe(true)
    expect(contrastRatio(result.accent, result.onAccent)).toBeGreaterThanOrEqual(MIN_CONTRAST)
    // 取不出颜色时整条链路如实返回 null，由调用方回退预置色。
    expect(deriveAdaptiveAccent(new Uint8ClampedArray([0, 0, 0, 255]), { theme: 'dark', source: 'image', surfaceOpacity: 86 })).toBeNull()
  })
})

describe('存储：三份互不覆盖的配置', () => {
  const sample = {
    accent: '#315fcf',
    onAccent: '#ffffff',
    background: '#101820',
    surface: '#18222c',
    surfaceOpacity: 86,
    source: 'image' as const,
    extractedAt: 1700000000000,
  }

  it('来源三态只认受控字面量，非法值回退 default', () => {
    expect(readAccentSource()).toBe('default')
    expect(saveAccentSource('adaptive')).toBe(true)
    expect(readAccentSource()).toBe('adaptive')
    expect(saveAccentSource('neon' as never)).toBe(false)
    window.localStorage.setItem(ACCENT_SOURCE_KEY, '{"mode":"adaptive"}')
    expect(readAccentSource()).toBe('default')
    expect(isAccentSource('custom')).toBe(true)
    expect(isAccentSource(3)).toBe(false)
  })

  it('自适应结果独立存储，损坏时按缺失处理', () => {
    expect(saveAdaptiveAccent(sample)).toBe(true)
    expect(readAdaptiveAccent()).toEqual(sample)
    window.localStorage.setItem(ADAPTIVE_ACCENT_KEY, '{"accent":"#fff"}')
    expect(readAdaptiveAccent()).toBeNull()
    window.localStorage.setItem(ADAPTIVE_ACCENT_KEY, 'not json')
    expect(readAdaptiveAccent()).toBeNull()
    clearAdaptiveAccent()
    expect(readAdaptiveAccent()).toBeNull()
  })

  it('切回自定义来源后，自适应结果仍在（互不覆盖）', () => {
    saveAdaptiveAccent(sample)
    saveAccentSource('adaptive')
    saveAccentSource('custom')
    expect(readAccentSource()).toBe('custom')
    expect(readAdaptiveAccent()).toEqual(sample)
  })

  it('同步记录只接受固定字段，非法配置拒绝写入', () => {
    const theme = { mode: 'dark' as const, accent: '#6fa8dc', background: '#101820', surface: '#18222c', surfaceOpacity: 86, syncBackground: false }
    const record = saveAccentSync(theme, 1700000000000)!
    expect(record.syncedAt).toBe(1700000000000)
    expect(readAccentSync()).toEqual(record)
    expect(saveAccentSync({ ...theme, mode: 'blue' as never })).toBeNull()
    expect(saveAccentSync({ ...theme, accent: '#6fa8d' })).toBeNull()
    expect(saveAccentSync({ ...theme, accent: 'url(https://example.com/x.png)' as never })).toBeNull()
    expect(saveAccentSync({ ...theme, surfaceOpacity: 101 })).toBeNull()
    expect(saveAccentSync({ ...theme, surfaceOpacity: 86.5 })).toBeNull()
    expect(saveAccentSync({ ...theme, surface: '<script>alert(1)</script>' as never })).toBeNull()
    window.localStorage.setItem(ACCENT_SYNC_KEY, '{"syncedAt":"yesterday","theme":{}}')
    expect(readAccentSync()).toBeNull()
    clearAccentSync()
    expect(readAccentSync()).toBeNull()
  })

  it('背景开关随主题一起落盘，老记录按 false 迁移', () => {
    const base = { mode: 'dark' as const, accent: '#6fa8dc', background: '#101820', surface: '#18222c', surfaceOpacity: 86 }
    // 老版本记录里没有这个字段：那时同步的一定只有颜色。
    expect(validateSyncedTheme(base).syncBackground).toBe(false)
    const withBackground = saveAccentSync({ ...base, syncBackground: true }, 1700000000000)!
    expect(withBackground.theme.syncBackground).toBe(true)
    expect(readAccentSync()?.theme.syncBackground).toBe(true)
    // 非布尔取值来自序列化错误或页面注入，不能替用户猜。
    expect(saveAccentSync({ ...base, syncBackground: 'true' as never })).toBeNull()
  })
})
