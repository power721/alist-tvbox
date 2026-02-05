import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

const API: string = 'http://127.0.0.1:4567'

// https://vitejs.dev/config/
export default defineConfig({
  build: {
    outDir: '../src/main/resources/static',
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('element-plus')) {
              return 'element-plus';
            }
            if (id.includes('@vue') || id.includes('vue')) {
              return 'framework';
            }
            return 'vendor';
          }
        },
      },
    },
  },
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
    }),
    Components({
      resolvers: [ElementPlusResolver()],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      '/api': API,
      '/bilibili': API,
      '/vod': API,
      '/vod1': API,
      '/sub': API,
      '/token': API,
      '/profiles': API,
      '/parse': API,
      '/play': API,
      '/live': API,
      '/images': API,
      '/history': API,
      '/tg-db': API,
    },
  },
})
