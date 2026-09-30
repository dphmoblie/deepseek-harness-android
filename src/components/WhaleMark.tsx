import whaleMark from '../assets/whale-mark.png'

/** 统一鲸鱼入口图标；素材来自用户提供的本地图片。 */
export function WhaleMark({ size = 24 }: { size?: number }) {
  return (
    <img className="whale-mark" src={whaleMark} width={size} height={size} alt="" aria-hidden="true" />
  )
}
