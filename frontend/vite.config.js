import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * Vite 配置
 * 版本：v0.03 里程碑：前后端分离脚手架
 *
 * 开发环境通过 proxy 把 /api 请求转发到 Spring Boot（8080），
 * 这样前端代码里只写 /api/xxx，不存在跨域问题；
 * 后端同时也开启了 CORS，两种方式任选其一。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    open: false,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
        // 后端接口本身以 /api 开头，因此不需要 rewrite
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1500
  }
})
