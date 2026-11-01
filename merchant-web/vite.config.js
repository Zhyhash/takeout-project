import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 后端默认端口 8080（application.yaml 未自定义）。
// 代理让浏览器视角保持同源，避免后端没有 CORS 配置导致的跨域失败。
const backendTarget = process.env.TAKEOUT_API_TARGET || 'http://localhost:8080'
const backendProxy = Object.fromEntries(
  ['/api', '/user', '/cart', '/order', '/rider', '/merchant', '/category', '/uploads', '/images']
    .map((prefix) => [prefix, backendTarget]),
)

export default defineConfig({
  base: './',
  plugins: [vue()],
  resolve: {
    alias: { '@rider-core': fileURLToPath(new URL('../src/main/resources/static/rider-web', import.meta.url)) },
  },
  server: {
    proxy: backendProxy,
    fs: { allow: [fileURLToPath(new URL('.', import.meta.url)), fileURLToPath(new URL('../src/main/resources/static/rider-web', import.meta.url))] },
  },
  preview: {
    proxy: backendProxy,
  },
})
