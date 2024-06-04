<script setup lang="ts">

import axios from "axios";
import {ElMessage} from "element-plus";
import {onMounted, ref} from "vue";

const youtubeCookie = ref('')

const getCookie = () => {
  axios.get('/api/settings/youtube_cookie').then(({data}) => {
    youtubeCookie.value = data.value
  })
}

const updateCookie = () => {
  axios.post('/api/settings', {name: 'youtube_cookie', value: youtubeCookie.value}).then(() => {
    ElMessage.success('更新成功')
  })
}

onMounted(() => {
  getCookie()
})
</script>

<template>
  <div>
    <el-form label-width="150px">
      <el-form-item label="YouTube登录Cookie" label-width="120">
        <el-input v-model="youtubeCookie" type="textarea" :rows="5"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="updateCookie">更新</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>

</style>
