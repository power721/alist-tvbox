import {createApp} from 'vue'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import JsonViewer from 'vue-json-viewer'
import App from './App.vue'
import router from './router'

import '@/services/axios.interceptors'

import './assets/main.css'
import accountService from "@/services/account.service";

// #1078:Windows 的 Edge/Chromium 会把 hidden 状态下的同帧 history 写入误判为页面
// 激活,调 ShowWindow(SW_RESTORE) 恢复最小化的窗口。vue-router 4.6.4 的
// beforeUnloadListener 恰好在 visibilitychange(hidden) 里保存滚动位置调 replaceState,
// 导致最小化后 ~70ms 被弹回。攒住 hidden 期间的 pushState/replaceState、只保留最后
// 一次,页面重新可见后补发(语义等价);顺带避免 hidden 写 history 干扰 bfcache 判定。
const deferHiddenHistoryWrites = () => {
  const nativePushState = History.prototype.pushState
  const nativeReplaceState = History.prototype.replaceState
  let pending: (() => void) | null = null
  const isHidden = () => document.visibilityState === 'hidden'

  History.prototype.pushState = function (this: History, ...args: Parameters<History['pushState']>) {
    if (isHidden()) {
      const self = this
      pending = () => nativePushState.apply(self, args)
      return
    }
    return nativePushState.apply(this, args)
  }
  History.prototype.replaceState = function (this: History, ...args: Parameters<History['replaceState']>) {
    if (isHidden()) {
      const self = this
      pending = () => nativeReplaceState.apply(self, args)
      return
    }
    return nativeReplaceState.apply(this, args)
  }
  document.addEventListener('visibilitychange', () => {
    if (isHidden() || !pending) return
    const flush = pending
    pending = null
    flush()
  })
}
deferHiddenHistoryWrites()

const app = createApp(App)

app.use(router)
app.use(ElementPlus)
app.use(JsonViewer)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

accountService.getInfo()

app.mount('#app')

router.beforeEach((to, from, next) => {
  const token = accountService.getToken()
  if (to.meta.auth && !token) {
    next({
      path: '/login',
      query: {redirect: to.fullPath}
    })
  } else {
    next()
  }
})
