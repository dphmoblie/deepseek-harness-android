import { useEffect, useRef, useState } from 'react'
import { BACKGROUND_EVENT, readBackground } from '../appearance'

/** 只渲染本地解密产生的对象 URL；隐藏页面和减少动态效果时暂停视频。 */
export function AppBackground() {
  const [media, setMedia] = useState<{ url: string; video: boolean } | null>(null)
  const video = useRef<HTMLVideoElement>(null)
  useEffect(() => {
    let generation = 0
    let objectUrl: string | undefined
    const refresh = async () => {
      const current = ++generation
      try {
        const blob = await readBackground()
        if (current !== generation) return
        if (objectUrl) URL.revokeObjectURL(objectUrl)
        objectUrl = blob ? URL.createObjectURL(blob) : undefined
        setMedia(blob && objectUrl ? { url: objectUrl, video: blob.type.startsWith('video/') } : null)
      } catch {
        // 存储不支持或文件损坏时使用普通主题，设置页提供清除入口。
      }
    }
    void refresh()
    const onChange = () => { void refresh() }
    window.addEventListener(BACKGROUND_EVENT, onChange)
    return () => { ++generation; window.removeEventListener(BACKGROUND_EVENT, onChange); if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [])
  useEffect(() => {
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    const sync = () => {
      if (!video.current) return
      if (document.hidden || reduced?.matches) video.current.pause()
      else void video.current.play().catch(() => undefined)
    }
    sync()
    document.addEventListener('visibilitychange', sync)
    reduced?.addEventListener('change', sync)
    return () => { document.removeEventListener('visibilitychange', sync); reduced?.removeEventListener('change', sync) }
  }, [media])
  return media && <div className="app-background" aria-hidden="true">
    {media.video ? <video ref={video} src={media.url} muted loop playsInline preload="metadata" /> : <img src={media.url} alt="" />}
  </div>
}
