<template>
  <div class="search">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl+'/vod'+token+'?wd=' + keyword" target="_blank">{{currentUrl}}/vod{{token}}?wd={{keyword}}</a>
    </div>

    <div>
      <el-input v-model="keyword" autocomplete="off" @change="search"/>
      <el-button type="primary" @click="search">搜索</el-button>
    </div>

    <h2>API返回数据</h2>
    <div class="data">
      <json-viewer :value="config" expanded copyable :expand-depth=3></json-viewer>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"

const token = ref('')
const keyword = ref('')
const config = ref('')
const currentUrl = window.location.origin

const search = function () {
  axios.get('/vod' + token.value + '?wd=' + keyword.value).then(({data}) => {
    config.value = data
  })
}

onMounted(() => {
  axios.get('/token').then(({data}) => {
    token.value = data ? '/' + data : ''
  })
})
</script>

<style scoped>
.description {
  margin-bottom: 12px;
}
</style>
