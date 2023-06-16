import {createApp} from 'vue'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import JsonViewer from 'vue-json-viewer'
import App from './App.vue'
import router from './router'

import '@/services/axios.interceptors'

import './assets/main.css'
import accountService from "@/services/account.service";

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
