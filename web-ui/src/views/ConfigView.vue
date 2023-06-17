<template>
  <el-form :model="form" label-width="120px">
    <el-form-item prop="enabledToken" label="安全订阅">
      <el-switch
        v-model="form.enabledToken"
        inline-prompt
        active-text="开启"
        inactive-text="关闭"
      />
    </el-form-item>
    <el-form-item prop="token" label="安全Token" v-if="form.enabledToken">
      <el-input v-model="form.token"/>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="updateToken">更新</el-button>
    </el-form-item>
  </el-form>

  <el-divider v-if="showLogin"/>

  <el-form :model="login" label-width="120px" v-if="showLogin">
    <el-form-item prop="token" label="强制登录AList">
      <el-switch
        v-model="login.enabled"
        inline-prompt
        active-text="开启"
        inactive-text="关闭"
      />
    </el-form-item>
    <el-form-item prop="username" label="用户名">
      <el-input v-model="login.username"/>
    </el-form-item>
    <el-form-item prop="password" label="密码">
      <el-input v-model="login.password" type="password" show-password/>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="updateLogin">保存</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import {onMounted, ref} from "vue";
import {ElMessage} from "element-plus";
import axios from "axios";

const showLogin = ref(false)
const login = ref({
  username: '',
  password: '',
  enabled: false
})

const form = ref({
  token: '',
  enabledToken: false
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

const updateLogin = () => {
  axios.post('/login', login.value).then(() => {
    ElMessage.success('保存成功')
  })
}

onMounted(() => {
  axios.get('/token').then(({data}) => {
    form.value.token = data
    form.value.enabledToken = data != ''
  })
  axios.get("/profiles").then(({data}) => {
    showLogin.value = data.includes('xiaoya')
    if (showLogin.value) {
      axios.get('/login').then(({data}) => {
        login.value = data
      })
    }
  })
})
</script>

<style>
</style>
