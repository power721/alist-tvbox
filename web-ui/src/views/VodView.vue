<template>
  <div class="vod">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl+'/vod'" target="_blank">{{currentUrl}}/vod</a>
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
  axios.get('/vod').then(({data}) => {
    config.value = data
  })
})
</script>

<style scoped>
.description {
  margin-bottom: 12px;
}
</style>
