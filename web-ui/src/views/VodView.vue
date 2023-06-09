<template>
  <div class="vod">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl+'/vod'" target="_blank">{{currentUrl}}/vod</a>
    </div>

    <div>
      <el-input v-model="id" autocomplete="off"/>
      <el-button type="primary" @click="getDetail">详情</el-button>
    </div>

    <div>
      <el-input v-model="path" autocomplete="off"/>
      <el-button type="primary" @click="load">加载</el-button>
    </div>

    <h2>API返回数据</h2>
    <div class="data">
      <pre><code>{{config}}</code></pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"

const id = ref('')
const path = ref('')
const config = ref('')
const currentUrl = window.location.origin

const getDetail = function () {
  axios.get('/vod?ids=' + id.value).then(({data}) => {
    config.value = data
  })
}

const load = function () {
  axios.get('/vod?t=' + path.value).then(({data}) => {
    config.value = data
  })
}

onMounted(() => {
  axios.get('/vod').then(({data}) => {
    config.value = data
  })
})
</script>

<style scoped>
.description {
  margin-bottom: 12px;
}
</style>
