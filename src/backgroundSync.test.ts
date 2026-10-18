import { describe, expect, it } from 'vitest'
import {
  MAX_SYNC_BASE64,
  SYNC_MIME,
  prepareBackgroundForSync,
  targetSize,
  validateSyncedBackground,
} from './backgroundSync'

const JPEG_BASE64 = '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q=='

describe('同步背景：尺寸计算', () => {
  it('长边超限时等比缩小，短边跟着算', () => {
    expect(targetSize(4000, 3000)).toEqual({ width: 1280, height: 960 })
    expect(targetSize(3000, 4000)).toEqual({ width: 960, height: 1280 })
    expect(targetSize(4000, 1000)).toEqual({ width: 1280, height: 320 })
  })

  it('小图不放大', () => {
    expect(targetSize(640, 480)).toEqual({ width: 640, height: 480 })
    expect(targetSize(1280, 720)).toEqual({ width: 1280, height: 720 })
  })

  it('非法尺寸回退到 1×1，不产生 NaN 画布', () => {
    expect(targetSize(0, 100)).toEqual({ width: 1, height: 1 })
    expect(targetSize(Number.NaN, 100)).toEqual({ width: 1, height: 1 })
  })
})

describe('同步背景：过桥校验', () => {
  it('接受 base64 + image/jpeg', () => {
    expect(validateSyncedBackground({ image: JPEG_BASE64, mime: SYNC_MIME })).toEqual({ image: JPEG_BASE64, mime: SYNC_MIME })
  })

  it('拒绝 CSS 片段、URL、非 base64 字符', () => {
    for (const image of ['url(https://example.com/a.png)', '";alert(1);//', 'a b', 'data:image/png;base64,AAA', '']) {
      expect(() => validateSyncedBackground({ image, mime: SYNC_MIME })).toThrow('背景内容无效')
    }
  })

  it('拒绝其它 mime 与超限载荷', () => {
    expect(() => validateSyncedBackground({ image: JPEG_BASE64, mime: 'image/png' })).toThrow('背景格式无效')
    const huge = 'A'.repeat(MAX_SYNC_BASE64 + 4)
    expect(() => validateSyncedBackground({ image: huge, mime: SYNC_MIME })).toThrow('背景内容无效')
    expect(() => validateSyncedBackground(null)).toThrow('背景参数无效')
    expect(() => validateSyncedBackground({ mime: SYNC_MIME })).toThrow('背景内容无效')
  })

  it('jsdom 没有画布解码能力时如实返回 null', async () => {
    const blob = new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' })
    await expect(prepareBackgroundForSync(blob)).resolves.toBeNull()
  })
})
