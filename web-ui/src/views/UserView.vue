<template>
  <el-form :model="form" :rules="rules" status-icon label-width="120px">
  <el-form-item prop="username" label="用户名">
    <el-input v-model="form.username"/>
  </el-form-item>
  <el-form-item prop="oldPassword" label="旧密码">
    <el-input type="password" v-model="form.oldPassword" show-password/>
  </el-form-item>
  <el-form-item prop="password" label="账号密码">
    <el-input type="password" v-model="form.password" show-password/>
  </el-form-item>
    <el-form-item prop="confirmPassword" label="确认密码">
      <el-input type="password" v-model="form.confirmPassword" show-password/>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="updateUsername">更新用户名</el-button>
      <el-button type="primary" @click="updatePassword">更新密码</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import {reactive, ref} from "vue";
import {ElMessage} from "element-plus";
import accountService from "@/services/account.service";

const form = ref({
  username: accountService.account.username,
  oldPassword: '',
  password: '',
  confirmPassword: '',
  authenticated: false
})
const rules = reactive({
  username: [
    {required: true, message: '请输入用户名', trigger: 'blur'}
  ],
})

const updateUsername = () => {
  const account = form.value
  if (!account.username) {
    ElMessage.error('用户名不能为空')
    return
  }

  accountService.update({
    username: account.username,
    password: '',
    oldPassword: '',
    authenticated: false,
  })
}

const updatePassword = () => {
  const account = form.value
  if (!account.username) {
    ElMessage.error('用户名不能为空')
    return
  }
  if (!account.oldPassword) {
    ElMessage.error('旧密码不能为空')
    return
  }
  if (!account.password) {
    ElMessage.error('密码不能为空')
    return
  }
  if (account.password !== account.confirmPassword) {
    ElMessage.error('密码不匹配')
    return
  }

  accountService.update({
    username: account.username,
    oldPassword: account.oldPassword,
    password: account.password,
    authenticated: false,
  })
}
</script>

<style>
</style>
