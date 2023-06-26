<template>
  <div class="vod">
    <h2>API地址</h2>
    <div class="description">
      <a :href="url" target="_blank">{{ currentUrl }}/vod{{ token }}</a>
    </div>

    <div>
      <el-input v-model="id" autocomplete="off" placeholder="vod_id"/>
      <el-button type="primary" @click="getDetail">资源详情</el-button>
    </div>

    <div>
      <el-input v-model="path" autocomplete="off" placeholder="目录完整路径"/>
      <el-button type="primary" @click="load">加载目录</el-button>
    </div>

    <h2>API返回数据</h2>
    <div class="data">
      <json-viewer :value="config" expanded copyable :expand-depth=5></json-viewer>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"

const token = ref('')
const id = ref('')
const path = ref('')
const url = ref('')
const config = ref('')
const currentUrl = window.location.origin

const getDetail = function () {
  url.value = currentUrl + '/vod' + token.value + '?ids=' + id.value
  axios.get('/vod' + token.value + '?ids=' + id.value).then(({data}) => {
    config.value = data
  })
}

const load = function () {
  url.value = currentUrl + '/vod' + token.value + '?t=' + path.value
  axios.get('/vod' + token.value + '?t=' + path.value).then(({data}) => {
    config.value = data
  })
}

onMounted(async () => {
  token.value = await axios.get('/token').then(({data}) => {
    return data ? '/' + data : ''
  })
  url.value = currentUrl + '/vod' + token.value
  axios.get('/vod' + token.value).then(({data}) => {
    config.value = data
  })
})
</script>

<style scoped>
.description {
  margin-bottom: 12px;
}
</style>
