import { beforeEach, describe, expect, it } from 'vitest'
import { BACKGROUND_PATH, clearBackgroundState, readBackgroundState, saveBackgroundState, validateBackgroundRef, validateBackgroundState } from './backgroundRef'

describe('背景地址校验', () => {
  it('接受内容提供者的视频与图片地址', () => {
    expect(validateBackgroundRef({
      uri: 'content://com.android.providers.media.documents/document/video%3A42',
      mime: 'video/mp4',
    })).toEqual({
      uri: 'content://com.android.providers.media.documents/document/video%3A42',
      mime: 'video/mp4',
      kind: 'video',
    })
    expect(validateBackgroundRef({ uri: 'content://media/external/images/media/7', mime: 'image/webp' }).kind).toBe('image')
  })

  it('拒绝非内容地址：file:// 会泄露私有路径，http(s):// 会绕开「只来自本机」', () => {
    for (const uri of [
      'file:///data/user/0/io.deepseekharness.mobile/files/a.mp4',
      'https://example.com/a.mp4',
      'content://',
      '',
      'javascript:alert(1)',
    ]) {
      expect(() => validateBackgroundRef({ uri, mime: 'video/mp4' })).toThrow('背景地址无效')
    }
  })

  it('拒绝不受支持的格式与坏 mime', () => {
    for (const mime of ['application/pdf', 'text/html', 'video', '', 'video/mp4; charset=utf-8']) {
      expect(() => validateBackgroundRef({ uri: 'content://media/external/video/media/1', mime })).toThrow(/背景/u)
    }
    expect(() => validateBackgroundRef({ uri: 'content://media/external/video/media/1', mime: 'application/pdf' }))
      .toThrow('背景只支持视频或图片')
  })

  it('拒绝非对象与超长地址', () => {
    expect(() => validateBackgroundRef(null)).toThrow('背景地址无效')
    expect(() => validateBackgroundRef({ uri: `content://${'a'.repeat(2100)}`, mime: 'video/mp4' })).toThrow('背景地址无效')
  })

  it('同源路径与原生保持一致', () => {
    expect(BACKGROUND_PATH).toBe('/__shell/background')
  })
})

describe('同步背景状态', () => {
  const ref = { uri: 'content://media/external/video/media/42', mime: 'video/mp4' }

  beforeEach(() => { window.localStorage.clear() })

  it('开关只认布尔：字符串或数字都算无效，不替用户猜', () => {
    expect(validateBackgroundState({ syncBackground: true, syncedAt: 1, background: ref }))
      .toEqual({ syncBackground: true, syncedAt: 1, background: { ...ref, kind: 'video' } })
    for (const syncBackground of ['true', 1, null, undefined]) {
      expect(() => validateBackgroundState({ syncBackground, syncedAt: 1, background: null })).toThrow('同步背景状态无效')
    }
  })

  it('没有背景时是 null，坏地址不放过桥', () => {
    expect(validateBackgroundState({ syncBackground: false, syncedAt: 0, background: null }).background).toBeNull()
    expect(() => validateBackgroundState({ syncBackground: true, syncedAt: 0, background: { uri: 'https://example.com/a.mp4', mime: 'video/mp4' } }))
      .toThrow('背景地址无效')
  })

  it('镜像可读写，坏内容回落到未同步过', () => {
    expect(readBackgroundState()).toEqual({ syncBackground: false, syncedAt: 0, background: null })
    expect(saveBackgroundState({ syncBackground: true, syncedAt: 1700000000000, background: { ...ref, kind: 'video' } })).toBe(true)
    expect(readBackgroundState()).toEqual({ syncBackground: true, syncedAt: 1700000000000, background: { ...ref, kind: 'video' } })
    window.localStorage.setItem('dsh.appearance.background-state', '{"syncBackground":"yes"}')
    expect(readBackgroundState()).toEqual({ syncBackground: false, syncedAt: 0, background: null })
    clearBackgroundState()
    expect(readBackgroundState()).toEqual({ syncBackground: false, syncedAt: 0, background: null })
  })
})
