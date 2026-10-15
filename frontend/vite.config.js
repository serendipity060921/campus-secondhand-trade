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
    // ★ 安全（C2 安全测试发现）：这里原来是 '0.0.0.0'，即开发服务器监听所有网卡。
    //   本机 vite 为 5.4.21，恰好命中「Windows 下 server.fs.deny 可被绕过」的漏洞
    //   （GHSA-fx2h-pf6j-xcff，修复版本需 >=6.4.3，5.x 无补丁），
    //   叠加 esbuild 的「任意网站可请求 dev server 并读取响应」问题，
    //   在校园网等不可信网络下存在被读取本机文件的风险。
    //   默认改为只监听回环地址：开发机自己访问不受任何影响，
    //   而局域网内的其他人无法再连到这个开发服务器。
    //   真机/手机调试时按需临时开启：pnpm dev --host 0.0.0.0（用完即关，勿长期挂着）。
    host: '127.0.0.1',
    port: 5173,
    open: false,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
        // 后端接口本身以 /api 开头，因此不需要 rewrite
      },
      // v0.05：商品图片上传后的访问路径（后端 FileUploadConfig 映射的静态目录）
      '/upload': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      // v0.14：实时私信 WebSocket（ws: true 让 Vite 代理升级协议，前端只需连同源的 /ws/chat）
      '/ws': {
        target: 'ws://127.0.0.1:8080',
        ws: true,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1500
  }
})
