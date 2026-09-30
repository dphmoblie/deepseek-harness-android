/**
 * 外壳自绘鲸鱼标记。
 *
 * 这里只保留抽象的鲸鱼轮廓，作为导航入口使用；不引用第三方图片或官方资源，
 * 避免把外部版权素材打包进 APK。颜色由调用方的 CSS 控制。
 */
export function WhaleMark({ size = 24 }: { size?: number }) {
  return (
    <svg
      aria-hidden="true"
      className="whale-mark"
      width={size}
      height={size}
      viewBox="0 0 48 48"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        d="M6.5 27.2c3.3-7.2 10.7-11.4 18.7-10.9 5.2.3 9.8 2.5 13.1 6.2 1.7 1.9 2.4 4 1.8 6.1-.7 2.6-3.3 4.3-6.5 4.3H22.8c-2.7 0-5.2-1.1-7.1-3.1l-1.3-1.4-4.8 4.3c-.8.7-2 .6-2.7-.2-.5-.7-.6-1.4-.4-2.1Z"
        fill="currentColor"
      />
      <path
        d="M32.9 16.8c.3-2.7 1.9-4.9 4.3-6.1-.2 2.2.4 4.1 1.9 5.6-1.8.1-3.8.3-6.2.5Z"
        fill="currentColor"
      />
      <path
        d="M17.2 27.7c1.5 2.2 3.6 3.3 6.3 3.3h10.6c1.2 0 2.1-.5 2.5-1.3.4-.9 0-1.9-1-2.9-2.6-2.7-6-4.2-9.7-4.4-3.4-.2-6.4.8-8.7 2.8Z"
        fill="var(--whale-mark-detail, var(--on-accent))"
        opacity=".8"
      />
      <circle cx="32.1" cy="23.3" r="1.4" fill="var(--whale-mark-detail, var(--on-accent))" />
    </svg>
  )
}
