<script setup lang="ts">
import {onMounted, onUnmounted, ref} from "vue";
import axios from "axios";
import {store} from "@/services/store";

const url = ref(window.location.protocol + '//' + window.location.hostname + ':' + (store.hostmode ? 5678 : 5344))
const iframeActive = ref(true)
const height = ref(window.innerHeight - 220) // 调整高度以适应新的页面结构
const installMode = ref('')

window.onresize = () => {
  height.value = window.innerHeight - 220
}

const loadBaseUrl = () => {
  if (store.baseUrl) {
    url.value = store.baseUrl
    return
  }

  if (!store.admin) {
    return
  }

  axios.get('/api/sites/1').then(({data}) => {
    url.value = data.url
    const re = /http:\/\/localhost:(\d+)/.exec(data.url)
    if (re) {
      url.value = window.location.protocol + '//' + window.location.hostname + ':' + re[1]
      store.baseUrl = url.value
      console.log('load AList ' + url.value)
    } else if (data.url == 'http://localhost') {
      axios.get('/api/alist/port').then(({data}) => {
        if (data) {
          url.value = window.location.protocol + '//' + window.location.hostname + ':' + data
          store.baseUrl = url.value
          console.log('load AList ' + url.value)
        }
      })
    } else {
      store.baseUrl = url.value
      console.log('load AList ' + url.value)
    }
  })
}

// 页面不可见(最小化/后台标签)时立即卸载内嵌 AList 的 iframe。
// iframe 里的 AList(含魔改版)自带轮询、标题刷新等持续脚本，顶层页面埋点不到也管不到；
// 挂在后台时这些脚本会阻止 Windows 上浏览器窗口保持最小化(#1078)，且弹回会让页面
// 重新可见、重置任何延迟卸载的计时，故必须立即卸载。回到页面时重新加载 iframe。
const onVisibilityChange = () => {
  if (document.hidden) {
    iframeActive.value = false
  } else {
    iframeActive.value = true
  }
}

onMounted(() => {
  loadBaseUrl()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">AList - TvBox</h1>
    </div>

    <div class="page-card">
      <div v-if="store.xiaoya">
        <el-text size="large">小雅集成版</el-text>
        <el-text v-if="store.native" size="small">内存优化</el-text>
        <el-text v-if="store.hostmode" size="small">host网络模式</el-text>
        <a :href="url" class="hint" target="_blank">{{ url }}</a>
      </div>
      <div v-else-if="store.docker">
        <el-text size="large">纯净版</el-text>
        <el-text v-if="store.native" size="small">内存优化</el-text>
        <a :href="url" class="hint" target="_blank">{{ url }}</a>
      </div>
      <div v-else>
        <el-text size="large">独立版</el-text>
        <a :href="url" class="hint" target="_blank">{{ url }}</a>
      </div>

      <iframe v-if="store.aListStatus && iframeActive" :src="url" :height="height" style="width: 100%; border: none; border-radius: 4px;">
      </iframe>
    </div>
  </div>
</template>
