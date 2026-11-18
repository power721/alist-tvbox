import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'MAINTENANCE_DEV_')
  const backend = env.MAINTENANCE_DEV_BACKEND || 'http://127.0.0.1:8797'
  const target = new URL(backend)
  if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1'
      || target.username || target.password || target.pathname !== '/' || target.search || target.hash) {
    throw new Error('MAINTENANCE_DEV_BACKEND must be a loopback HTTP origin')
  }
  const proxy = { target: backend, autoRewrite: true }
  return {
    base: './',
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      proxy: {
        '/api': proxy,
        '/login': proxy,
        '/logout': proxy
      }
    }
  }
})
