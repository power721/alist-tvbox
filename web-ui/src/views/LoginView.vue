<template>
  <el-alert title="关于密码加强" type="warning" :description="alert" show-icon v-if="showAlert" @close="closeAlert"/>
  <div class="space"></div>

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
import {onMounted, reactive, ref} from "vue";
import {useRoute, useRouter} from "vue-router";
import accountService from "@/services/account.service";

const alert = '本次升级会强制更改帐号为随机密码。' +
  '请从Docker日志搜索“密码”或者数据目录的initial_admin_credentials.txt文件查看密码。' +
  '如果找不到密码，ssh到系统运行：sudo bash -c "$(curl -fsSL http://d.har01d.cn/alist-tvbox.sh)"' +
  ' 选择菜单8和8，重置密码。' +
  'WebDAV使用配置页面设置的用户名和密码。'

const showAlert = ref(true)
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

const closeAlert = () => {
  localStorage.setItem('password-alert', 'no')
}

onMounted(() => {
  showAlert.value = !localStorage.getItem('password-alert')
})
</script>

<style scoped>

</style>
