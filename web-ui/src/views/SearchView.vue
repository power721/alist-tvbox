<template>
  <div class="search">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl+getPath(type)+token+'?wd=' + keyword" target="_blank">{{currentUrl}}{{getPath(type)}}{{token}}?wd={{keyword}}</a>
    </div>

    <div>
      <el-input v-model="keyword" @change="search"/>
      <el-button type="primary" @click="search" :disabled="!keyword">搜索</el-button>
    </div>

    <el-form-item label="类型" label-width="140">
      <el-radio-group v-model="type" @change="search" class="ml-4">
        <el-radio label="1" size="large">点播模式</el-radio>
        <el-radio label="" size="large">网盘模式</el-radio>
        <el-radio label="2" size="large">BiliBili</el-radio>
        <el-radio label="3" size="large">YouTube</el-radio>
        <el-radio label="4" size="large">Emby</el-radio>
        <el-radio label="5" size="large">Jellyfin</el-radio>
      </el-radio-group>
    </el-form-item>

    <a href="/#/meta">豆瓣电影数据列表</a>
    <span class="divider"></span>
    <a href="/#/tmdb">TMDB电影数据列表</a>

    <el-table v-if="(type==''||type=='1')&&config" :data="config.list" border style="width: 100%">
      <el-table-column prop="vod_name" label="名称" width="300">
        <template #default="scope">
          <a :href="scope.row.vod_play_url" target="_blank">
            {{ scope.row.vod_name }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_content" label="路径">
        <template #default="scope">
          <a :href="scope.row.vod_play_url" target="_blank">
            {{ scope.row.vod_content }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_year" label="年份" width="90" />
      <el-table-column prop="vod_remarks" label="评分" width="100" />
    </el-table>

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
const config = ref<any>('')
const currentUrl = window.location.origin

const getPath = (type: string) => {
  if (type == '1') {
    return '/vod1'
  } else if (type == '2') {
    return '/bilibili'
  } else if (type == '3') {
    return '/youtube'
  } else if (type == '4') {
    return '/emby'
  } else if (type == '5') {
    return '/jellyfin'
  } else {
    return '/vod'
  }
}

const search = function () {
  if (!keyword.value) {
    return
  }
  config.value = ''
  axios.get(getPath(type.value) + token.value + '?ac=web&wd=' + keyword.value.trim()).then(({data}) => {
    config.value = data
  })
}

onMounted(() => {
  axios.get('/api/token').then(({data}) => {
    token.value = data ? '/' + (data + '').split(',')[0] : ''
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
