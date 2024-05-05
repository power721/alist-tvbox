<template>
  <div class="vod">
    <h2>API地址</h2>
    <div class="description">
      <a :href="url" target="_blank">{{ currentUrl }}{{ getPath(type) }}{{ token }}</a>
    </div>

    <div>
      <el-input v-model="id" @keyup.enter="getDetail" placeholder="vod_id"/>
      <el-button type="primary" @click="getDetail">资源详情</el-button>
    </div>

    <div>
      <el-input v-model="path" @keyup.enter="load" placeholder="目录完整路径"/>
      <el-button type="primary" @click="load">加载目录</el-button>
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
      <json-viewer :value="config" expanded copyable show-double-quotes :show-array-index="false" :expand-depth=5></json-viewer>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"
import {store} from "@/services/store";

const token = ref('')
const type = ref('1')
const id = ref('')
const path = ref('')
const url = ref('')
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

const getDetail = function () {
  url.value = currentUrl + getPath(type.value) + token.value + '?ids=' + id.value
  axios.get(getPath(type.value) + token.value + '?ids=' + id.value).then(({data}) => {
    config.value = data
  })
}

const load = function () {
  url.value = currentUrl + getPath(type.value) + token.value + '?t=' + path.value
  axios.get(getPath(type.value) + token.value + '?t=' + path.value ).then(({data}) => {
    config.value = data
  })
}

onMounted(async () => {
  token.value = await axios.get('/api/token').then(({data}) => {
    return data ? '/' + (data + '').split(',')[0] : ''
  })
  url.value = currentUrl + getPath(type.value) + token.value
  axios.get(getPath(type.value) + token.value).then(({data}) => {
    config.value = data
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
