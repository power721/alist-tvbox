<template>
  <el-form :model="form" label-width="120px">
    <el-form-item prop="token" label="安全订阅">
      <el-switch
        v-model="form.enabledToken"
        inline-prompt
        active-text="开启"
        inactive-text="关闭"
      />
    </el-form-item>
    <el-form-item prop="username" label="安全Token" v-if="form.enabledToken">
      <el-input v-model="form.token"/>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="updateToken">更新</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from "vue";
import {ElMessage} from "element-plus";
import accountService from "@/services/account.service";
import axios from "axios";

const form = ref({
  token: '',
  enabledToken: false
})

const rules = reactive({

})

const updateToken = () => {
  if (form.value.enabledToken) {
    axios.post('/token', {token: form.value.token}).then(({data}) => {
      form.value.token = data
      ElMessage.success('成功开启安全订阅')
    })
  } else {
    axios.delete('/token').then(() => {
      form.value.token = ''
      ElMessage.success('成功关闭安全订阅')
    })
  }
}

onMounted(() => {
  axios.get('/token').then(({data}) => {
    form.value.token = data
    form.value.enabledToken = data != ''
  })
})
</script>

<style>
</style>
