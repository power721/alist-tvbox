<script setup lang="ts">

import {onMounted, ref} from "vue";
import axios from "axios";

const page = ref(1)
const count = ref(0)
const total = ref(0)
const logs = ref([])

const load = (pageNumber: number) => {
  page.value = pageNumber
  axios.get('/logs?size=50&page=' + pageNumber).then(({data}) => {
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
  <div>
    <el-button type="primary" @click="download">下载日志</el-button>
    <el-pagination layout="prev, pager, next" :page-size="50" :current-page="page" :total="total"
                   @current-change="load"/>
  </div>
  <div v-for="log of logs" v-html="log"></div>
  <div v-if="count >= 50">
    <el-pagination layout="prev, pager, next" :page-size="50" :current-page="page" :total="total"
                   @current-change="load"/>
  </div>
</template>

<style scoped>
</style>
