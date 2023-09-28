<script setup lang="ts">

import {onMounted, ref} from "vue";
import axios from "axios";
import {store} from "@/services/store";

const type = ref('app')
const page = ref(1)
const count = ref(0)
const total = ref(0)
const logs = ref([])

const reload = () => {
  load(page.value)
}

const onTypeChange = () => {
  load(1)
}

const load = (pageNumber: number) => {
  page.value = pageNumber
  axios.get('/api/logs?type='+type.value+'&size=50&page=' + (pageNumber - 1)).then(({data}) => {
    logs.value = data.content
    total.value = data.totalElements
    count.value = data.numberOfElements
  })
}

const download = () => {
  window.location.href = '/logs/download?t=' + new Date().getTime();
}

onMounted(() => {
  load(1)
})

</script>

<template>

  <el-form-item label="类型" label-width="140" v-if="store.xiaoya">
    <el-radio-group v-model="type" @change="onTypeChange" class="ml-4">
      <el-radio label="app" size="large">应用日志</el-radio>
      <el-radio label="alist" size="large">AList日志</el-radio>
      <el-radio label="init" size="large">启动日志</el-radio>
    </el-radio-group>
  </el-form-item>

  <div class="flex">
    <el-pagination layout="prev, pager, next" :page-size="50" :current-page="page" :total="total"
                   @current-change="load"/>
    <div>
      <el-button type="primary" @click="reload">刷新</el-button>
      <el-button type="primary" class="download" @click="download">下载日志</el-button>
    </div>
  </div>
  <div v-for="log of logs" v-html="log"></div>
  <div v-if="count >= 50">
    <el-pagination layout="prev, pager, next" :page-size="50" :current-page="page" :total="total"
                   @current-change="load"/>
  </div>
</template>

<style scoped>
.flex {
  display: flex;
  flex-direction: row;
  justify-content: flex-start;
  gap: 0 24px;
}
</style>
