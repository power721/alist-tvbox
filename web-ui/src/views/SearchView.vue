<template>
  <div class="search">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl+'/vod?wd=' + keyword" target="_blank">{{currentUrl}}/vod?wd={{keyword}}</a>
    </div>

    <div>
      <el-input v-model="keyword" autocomplete="off" @change="search"/>
      <el-button @click="search">搜索</el-button>
    </div>

    <h2>API返回数据</h2>
    <div class="data">
      <pre><code>{{config}}</code></pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import axios from "axios"

const keyword = ref('')
const config = ref('')
const currentUrl = window.location.origin

const search = function () {
  axios.get('/vod?wd=' + keyword.value).then(({data}) => {
    config.value = data
  })
}

</script>

<style scoped>
.description {
  margin-bottom: 12px;
}
</style>
