import { describe, expect, it } from 'vitest'
import {
  MAX_VIDEO_BYTES,
  SYNC_CHUNK_BYTES,
  SYNC_IMAGE_MIME,
  SYNC_VIDEO_MIME,
  backgroundMediaKind,
  toBase64,
  uploadBackgroundInChunks,
  validateBackgroundUpload,
} from './backgroundUpload'

function blobOf(bytes: number, type: string): Blob {
  return new Blob([new Uint8Array(bytes).fill(7)], { type })
}

describe('背景分块上传：校验', () => {
  it('接受受控的 video/mp4 与 image/jpeg 分块', () => {
    expect(validateBackgroundUpload({ mime: SYNC_VIDEO_MIME, index: 0, data: 'AAAA', done: false }))
      .toEqual({ mime: SYNC_VIDEO_MIME, index: 0, data: 'AAAA', done: false })
    expect(validateBackgroundUpload({ mime: SYNC_IMAGE_MIME, index: 3, data: '/9j/', done: true }).done).toBe(true)
  })

  it('拒绝未知 mime、负数序号、非 base64 与超长块', () => {
    for (const value of [
      { mime: 'video/webm', index: 0, data: 'AAAA', done: false },
      { mime: SYNC_VIDEO_MIME, index: -1, data: 'AAAA', done: false },
      { mime: SYNC_VIDEO_MIME, index: 1.5, data: 'AAAA', done: false },
      { mime: SYNC_VIDEO_MIME, index: 0, data: 'url(https://x)', done: false },
      { mime: SYNC_VIDEO_MIME, index: 0, data: 'A'.repeat(512 * 1024 + 4), done: false },
      { mime: SYNC_VIDEO_MIME, index: 0, data: '', done: false },
      { mime: SYNC_VIDEO_MIME, index: 0, data: 'AAAA' },
    ]) {
      expect(() => validateBackgroundUpload(value)).toThrow(/背景/iu)
    }
    expect(() => validateBackgroundUpload(null)).toThrow('背景分块无效')
  })

  it('二进制转 base64 不会在长数据上爆栈', () => {
    const bytes = new Uint8Array(200_000).fill(65)
    const encoded = toBase64(bytes)
    expect(encoded.startsWith('QUFB')).toBe(true)
    expect(encoded.length).toBe(Math.ceil(bytes.length / 3) * 4)
  })
})

describe('背景分块上传：切块与上限', () => {
  it('按 192 KB 切块，最后一块标记 done', async () => {
    const sent: { index: number, done: boolean, bytes: number }[] = []
    const size = SYNC_CHUNK_BYTES * 2 + 10
    const result = await uploadBackgroundInChunks(blobOf(size, 'video/mp4'), chunk => {
      sent.push({ index: chunk.index, done: chunk.done, bytes: chunk.data.length })
      expect(chunk.mime).toBe(SYNC_VIDEO_MIME)
      return Promise.resolve()
    })
    expect(result).toEqual({ kind: 'video', bytes: size })
    expect(sent.map(item => item.index)).toEqual([0, 1, 2])
    expect(sent.map(item => item.done)).toEqual([false, false, true])
  })

  it('图片走 image/jpeg 的声明，类型由 blob 决定', async () => {
    const result = await uploadBackgroundInChunks(blobOf(32, 'image/png'), chunk => {
      expect(chunk.mime).toBe(SYNC_IMAGE_MIME)
      return Promise.resolve()
    }, { chunkBytes: 16 })
    expect(result.kind).toBe('image')
  })

  it('空文件与超限文件直接拒绝，不发任何块', async () => {
    let calls = 0
    await expect(uploadBackgroundInChunks(blobOf(0, 'video/mp4'), () => { calls += 1; return Promise.resolve() })).rejects.toThrow('背景文件是空的')
    await expect(uploadBackgroundInChunks(blobOf(64, 'video/mp4'), () => { calls += 1; return Promise.resolve() }, { maxBytes: 32 }))
      .rejects.toThrow('背景文件过大')
    expect(calls).toBe(0)
  })

  it('中途失败会把错误抛给调用方，不假装成功', async () => {
    await expect(uploadBackgroundInChunks(blobOf(SYNC_CHUNK_BYTES * 3, 'video/mp4'), chunk => {
      if (chunk.index === 1) return Promise.reject(new Error('磁盘满了'))
      return Promise.resolve()
    })).rejects.toThrow('磁盘满了')
  })

  it('媒体类型映射只认两种受控 mime', () => {
    expect(backgroundMediaKind(SYNC_VIDEO_MIME)).toBe('video')
    expect(backgroundMediaKind(SYNC_IMAGE_MIME)).toBe('image')
    expect(backgroundMediaKind('video/webm')).toBeNull()
  })
})

describe('视频上限', () => {
  it('默认上限是 32 MB', () => {
    expect(MAX_VIDEO_BYTES).toBe(32 * 1024 * 1024)
  })
})
