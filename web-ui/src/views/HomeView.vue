<script setup lang="ts">
import {onMounted, ref} from "vue";
import axios from "axios";
import {store} from "@/services/store";

const url = ref('http://' + window.location.hostname + ':5344')
const height = ref(window.innerHeight - 175)
const width = ref(window.innerWidth - 40)

window.onresize = () => {
  height.value = window.innerHeight - 175
  width.value = window.innerWidth - 40
}

const loadBaseUrl = () => {
  if (store.baseUrl) {
    url.value = store.baseUrl
    return
  }

  axios.get('/api/sites/1').then(({data}) => {
    url.value = data.url
    const re = /http:\/\/localhost:(\d+)/.exec(data.url)
    if (re) {
      url.value = 'http://' + window.location.hostname + ':' + re[1]
      store.baseUrl = url.value
      console.log('load AList ' + url.value)
    } else if (data.url == 'http://localhost') {
      axios.get('/api/alist/port').then(({data}) => {
        if (data) {
          url.value = 'http://' + window.location.hostname + ':' + data
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

onMounted(() => {
  loadBaseUrl()
})
</script>

<template>
  <div>
    <h1>
      AList - TvBox
    </h1>
    <div v-if="store.xiaoya">
      <el-text size="large">小雅集成版</el-text>
      <el-text v-if="store.hostmode" size="small">host网络模式</el-text>
      <a :href="url" class="hint" target="_blank">{{ url }}</a>
    </div>
    <div v-else>
      <el-text size="large">纯净版</el-text>
      <a :href="url" class="hint" target="_blank">{{ url }}</a>
    </div>
    <iframe v-if="store.aListStatus" :src="url" :width="width" :height="height">
    </iframe>
  </div>
</template>
