import { beforeEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AppearanceSettings } from './AppearanceSettings'
import { saveLanguage } from '../i18n'

const video = () => new Blob(['video-bytes'], { type: 'video/mp4' })

describe('背景选择状态文案', () => {
  beforeEach(() => {
    saveLanguage('zh-CN')
    window.localStorage.clear()
  })

  it('已经存过背景时显示「已选择背景」而不是格式说明', async () => {
    render(<AppearanceSettings scope="background" loadBackground={() => Promise.resolve(video())} />)

    expect(await screen.findByText('已选择背景，重新选择会替换当前背景')).toBeInTheDocument()
    expect(screen.queryByText('支持 PNG、JPG、WebP、GIF、MP4、WebM，最大 50 MB')).toBeNull()
  })

  it('存储读取失败时保留已选标记，不把背景说成没选', async () => {
    window.localStorage.setItem('dsh-background-present-v1', '1')
    render(<AppearanceSettings scope="background" loadBackground={() => Promise.reject(new Error('解密失败'))} />)

    expect(await screen.findByText('已选择背景，重新选择会替换当前背景')).toBeInTheDocument()
  })
})
