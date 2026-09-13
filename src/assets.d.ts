// 静态图片资源的模块声明。
// tsconfig.app.json 的 types 未引入 vite/client，这里按需声明最小集合，
// 避免引入整套 Vite 类型影响既有类型检查。
declare module '*.png' {
  const source: string
  export default source
}
