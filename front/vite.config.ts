import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: process.env.RAG_BACKEND_URL || 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
      // 独立问答页（悬浮窗内嵌内容）：页面由 RAG 后端提供（classpath:/static/emb/index.html），
      // 这里只是让开发地址 http://localhost:5173/emb 也能直接打开同一个页面。
      '/emb': {
        target: process.env.RAG_BACKEND_URL || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
