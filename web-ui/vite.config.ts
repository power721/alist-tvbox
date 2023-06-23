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
      "/accounts": API,
      "/ali-accounts": API,
      "/login": API,
      "/checkin": API,
      "/files": API,
      "/sites": API,
      "/shares": API,
      "/resources": API,
      "/subscriptions": API,
      "/settings": API,
      "/schedule": API,
      "/storage": API,
      "/vod": API,
      "/sub": API,
      "/token": API,
      "/profiles": API,
      "/index": API,
      "/index-templates": API,
      "/movies": API,
      "/tasks": API,
      "/parse": API,
      "/play": API,
      "/playlist": API,
      "/alist": API,
      "/movie": API,
      "/versions": API,
      "/import-shares": API,
      "/delete-shares": API,
    }
  }
})
