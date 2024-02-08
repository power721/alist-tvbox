<template>
  <div class="search">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl+getPath(type)+token+'?wd=' + keyword" target="_blank">{{currentUrl}}{{getPath(type)}}{{token}}?wd={{keyword}}</a>
    </div>

    <div>
      <el-input v-model="keyword" autocomplete="off"/>
      <el-button type="primary" @click="search" :disabled="!keyword">搜索</el-button>
    </div>

    <el-form-item label="类型" label-width="140">
      <el-radio-group v-model="type" class="ml-4">
        <el-radio label="1" size="large">点播模式</el-radio>
        <el-radio label="" size="large">网盘模式</el-radio>
        <el-radio label="2" size="large">BiliBili</el-radio>
      </el-radio-group>
    </el-form-item>

    <a href="/#/meta">豆瓣电影数据列表</a>
    <span class="divider"></span>
    <a href="/#/tmdb">TMDB电影数据列表</a>

    <h2>API返回数据</h2>
    <div class="data">
      <json-viewer :value="config" expanded copyable show-double-quotes :show-array-index="false" :expand-depth=3></json-viewer>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"
import {store} from "@/services/store";

const token = ref('')
const type = ref('1')
const keyword = ref('')
const config = ref('')
const currentUrl = window.location.origin

const getPath = (type: string) => {
  if (type == '2') {
    return '/bilibili'
  } else if (type == '1') {
    return '/vod1'
  } else {
    return '/vod'
  }
}

const search = function () {
  axios.get(getPath(type.value) + token.value + '?wd=' + keyword.value).then(({data}) => {
    config.value = data
  })
}

onMounted(() => {
  axios.get('/api/token').then(({data}) => {
    token.value = data ? '/' + data : ''
  })
})
</script>

<style scoped>
.description {
  margin-bottom: 12px;
}
.divider {
  margin-left: 24px;
}
</style>
