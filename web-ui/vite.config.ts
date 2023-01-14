import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const API = "http://127.0.0.1:5678";

// https://vitejs.dev/config/
export default defineConfig({
  build: {
    outDir: "../src/main/resources/static"
  },
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    proxy: {
      "/sites": API,
      "/vod": API,
      "/sub": API,
      "/index": API,
      "/movies": API,
      "/parse": API,
      "/play": API,
      "/playlist": API,
    }
  }
})
