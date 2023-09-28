<script setup lang="ts">
import {onMounted, ref} from "vue";
import axios from "axios";
import {store} from "@/services/store";

const url = ref('http://' + window.location.hostname + ':5244')
const height = ref(window.innerHeight - 175)
const width = ref(window.innerWidth - 40)

window.onresize = () => {
  height.value = window.innerHeight - 175
  width.value = window.innerWidth - 40
}

onMounted(() => {
  if (store.xiaoya) {
    axios.get('/api/sites/1').then(({data}) => {
      const re = /http:\/\/localhost:(\d+)/.exec(data.url)
      if (re) {
        url.value = 'http://' + window.location.hostname + ':' + re[1]
      } else if (data.url == 'http://localhost') {
        axios.get('/api/alist/port').then(({data}) => {
          if (data) {
            url.value = 'http://' + window.location.hostname + ':' + data
          }
        })
      } else {
        url.value = data.url
      }
      console.log('load AList ' + url.value)
    })
  }
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
      <a :href="url" class="hint" target="_blank">{{url}}</a>
    </div>
    <el-text v-else size="large">独立版</el-text>
    <iframe v-if="store.xiaoya&&store.aListStatus" :src="url" :width="width" :height="height">
    </iframe>
  </div>
</template>
