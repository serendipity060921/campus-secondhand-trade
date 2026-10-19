import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * Vitest 配置（v0.18 / 第6批-㉒）
 *
 * 与 vite.config.js 分开写，原因：
 *   · 组件测试需要 jsdom 环境与 globals，而这些不该污染生产构建配置；
 *   · include 只指向 tests/component，避免把 e2e / 纯函数测试混进来
 *     （那两类由 node + c8 单独跑，见 package.json 的 test:unit / test:coverage）。
 *
 * 位置说明：组件测试放在 frontend/tests/component（而不是仓库根的 tests/），\n * 因为 setup.js 要 import '@vue/test-utils' —— 裸模块解析从文件所在目录开始，\n * 放在项目根外会解析不到（实测踩过）。纯函数测试仍在 tests/unit，用 node 直接跑。\n *\n * 覆盖率口径（与后端 JaCoCo 的"棘轮"思路一致，但门槛另设）：
 *   · 只统计 src/components 与 src/api —— 页面（views）依赖路由与真实接口，
 *     其行为由端到端用例覆盖，不纳入行覆盖率统计（否则数字好看但没意义）；
 *   · thresholds 设为**当前实测值向下取整**，只许上升。
 */
export default defineConfig({
  plugins: [vue()],
  // 测试文件放在仓库根的 tests/component（与 tests/unit 一致），在项目根之外，
  // 必须显式放开 Vite 的 fs 白名单，否则 setup.js 会报 Failed to load url。
  server: { fs: { allow: ['..'] } },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['tests/component/**/*.spec.js'],
    // Element Plus 的组件在测试里一律用轻量 stub，不需要真实渲染整套 UI
    setupFiles: ['tests/component/setup.js'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      // ★ 统计范围是显式声明的：本次只对「已编写组件测试」的模块算覆盖率。
      //   其余组件与接口暂无组件级测试，其行为由端到端用例覆盖（见 docs/前端组件测试说明.md）。
      //   把未测模块算进来只会得到一个「很低但无从下手」的整体数（实测 24.49%）。
      //   这与后端排除 Lombok 生成代码同理：改变口径必须写明理由，然后按口径设棘轮。
      // 只算 components：src/api 是薄封装（一行一个 axios 调用），组件测试里必然被 mock，
      // 计进来的话恒为 0%，而它的正确性由后端接口的端到端实测负责。
      include: ['src/components/evaluation/**'],
      thresholds: {
        // 实测：lines 98.15% / statements 98.15% / branches 80.70% / functions 50%
        // 门槛在实测值下方向下取整，只许上升。
        // functions 偏低是因为 Vue SFC 模板里的内联箭头函数也被 v8 计入统计。
        lines: 95,
        functions: 45,
        statements: 95,
        branches: 75
      }
    }
  }
})
