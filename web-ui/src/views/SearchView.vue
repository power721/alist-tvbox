<template>
  <div class="search">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl+'/vod'+token+'?wd=' + keyword" target="_blank">{{currentUrl}}/vod{{token}}?wd={{keyword}}</a>
    </div>

    <div>
      <el-input v-model="keyword" autocomplete="off"/>
      <el-button type="primary" @click="search">搜索</el-button>
    </div>

    <el-form-item label="类型" label-width="140" v-if="store.xiaoya">
      <el-radio-group v-model="type" class="ml-4">
        <el-radio label="" size="large">网盘模式</el-radio>
        <el-radio label="1" size="large">点播模式</el-radio>
      </el-radio-group>
    </el-form-item>

    <h2>API返回数据</h2>
    <div class="data">
      <json-viewer :value="config" expanded copyable :expand-depth=3></json-viewer>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"
import {store} from "@/services/store";

const token = ref('')
const type = ref('')
const keyword = ref('')
const config = ref('')
const currentUrl = window.location.origin

const search = function () {
  axios.get('/vod' + type.value + token.value + '?wd=' + keyword.value).then(({data}) => {
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
