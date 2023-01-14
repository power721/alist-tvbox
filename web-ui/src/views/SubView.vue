<template>
  <div class="config">
    <div>
      <h2>TvBox订阅地址</h2>
      <a :href="currentUrl+'/sub/0'" target="_blank">{{currentUrl}}/sub/0</a>
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

const config = ref('')
const currentUrl = window.location.origin

onMounted(() => {
  axios.get('/sub/0').then(({data}) => {
    config.value = data
  })
})
</script>

<style scoped>
</style>
