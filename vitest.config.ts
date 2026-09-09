import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    include: ['src/**/*.test.{ts,tsx}'],
    css: true,
    // Node.js 25+ 的原生网页存储会覆盖 jsdom 存储；测试进程使用浏览器模拟实现。
    poolOptions: {
      forks: { execArgv: Number(process.versions.node.split('.')[0]) >= 25 ? ['--no-experimental-webstorage'] : [] },
      threads: { execArgv: Number(process.versions.node.split('.')[0]) >= 25 ? ['--no-experimental-webstorage'] : [] },
    },
  },
})
