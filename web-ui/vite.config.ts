import {fileURLToPath, URL} from 'node:url'

import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

const API: string = "http://127.0.0.1:4567";

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
      "/api": API,
      "/bilibili": API,
      "/vod": API,
      "/vod1": API,
      "/sub": API,
      "/token": API,
      "/profiles": API,
      "/parse": API,
      "/play": API,
      "/live": API,
    }
  }
})
