<template>
  <el-form :model="account" :rules="rules" status-icon label-width="120px">
    <el-form-item prop="username" label="用户名">
      <el-input v-model="account.username"/>
    </el-form-item>
    <el-form-item prop="password" label="密码">
      <el-input type="password" v-model="account.password" @keyup.enter="login" show-password/>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="login">登录</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import {reactive, ref} from "vue";
import {useRoute, useRouter} from "vue-router";
import accountService from "@/services/account.service";

const route = useRoute()
const router = useRouter()
const account = ref({
  username: accountService.account.username,
  password: '',
  rememberMe: true,
  authenticated: false
})
const rules = reactive({
  username: [
    {required: true, message: '请输入用户名', trigger: 'blur'}
  ],
  password: [
    {required: true, message: '请输入密码', trigger: 'blur'}
  ]
})

const login = () => {
  accountService.login(account.value).then(() => {
    const back = (route.query.redirect as string) || '/'
    setTimeout(() => router.push(back), 500)
  })
}
</script>

<style scoped>

</style>
