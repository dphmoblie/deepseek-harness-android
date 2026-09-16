import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    include: ['src/**/*.test.{ts,tsx}'],
    css: true,
    /*
     * 单条用例的超时（默认 5 秒不够）。
     *
     * **为什么必须显式给**：这套用例里有大量「挂载整个 App + 驱动轮询」的重用例，
     * 单文件在空闲机器上约 3–16 秒，在**并发满载**或 CI 的 2 核 runner 上会成倍放大。
     * 实测证据：同一条用例单独跑 3.2 秒通过、并发跑时被 5 秒上限判失败；
     * 把上限提到 30 秒后失败文件数从 8 降到 2（剩下那 1 条是另一个原因，另行修）。
     * 也就是说此前的「失败」里混着大量**超时假阴性**——它们既掩盖真实回归，又让 CI 结论不可信。
     *
     * 20 秒仍然抓得住真正的挂起（正常用例最慢是数百毫秒量级），因此不是「一把梭放宽」，
     * 而是把上限放在「比最慢的真实用例高一个数量级、又远低于挂起」的位置。
     */
    testTimeout: 20_000,
    // Node.js 25+ 的原生网页存储会覆盖 jsdom 存储；测试进程使用浏览器模拟实现。
    poolOptions: {
      forks: { execArgv: Number(process.versions.node.split('.')[0]) >= 25 ? ['--no-experimental-webstorage'] : [] },
      threads: {
        execArgv: Number(process.versions.node.split('.')[0]) >= 25 ? ['--no-experimental-webstorage'] : [],
        /*
         * 限制 worker 数：本仓库有 25 个 jsdom 用例文件，默认按 CPU 核数铺满，
         * 在 20 核开发机上就会互相饿死，CI 的 2 核 runner 只会更糟。
         * 上限 4 是「够并行、又不至于每条用例都在抢 CPU」的折中；代价是墙钟变长，
         * 换来的是**结论可信**——宁可慢一点，也不要红的绿的都不可信。
         */
        maxThreads: 4,
        minThreads: 1,
      },
    },
  },
})
