import { describe, expect, it } from 'vitest'
import { BACKGROUND_PATH, validateBackgroundRef } from './backgroundRef'

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
