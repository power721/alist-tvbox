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
  <el-divider/>
  <h3>登录会话</h3>
  <el-table :data="sessions" border style="width: 100%; min-width: 600px">
    <el-table-column label="登录时间" min-width="180">
      <template #default="{ row }">{{ formatTime(row.loginTime) }}</template>
    </el-table-column>
    <el-table-column label="设备" min-width="180">
      <template #default="{ row }">
        <el-tooltip v-if="row.userAgent" :content="row.userAgent" placement="top">
          <span>{{ row.browser }} · {{ row.os }}</span>
        </el-tooltip>
        <span v-else>{{ row.browser }} · {{ row.os }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="loginIp" label="IP" width="150"/>
    <el-table-column label="过期时间" min-width="180">
      <template #default="{ row }">{{ formatTime(row.expireTime) }}</template>
    </el-table-column>
    <el-table-column fixed="right" label="操作" width="160">
      <template #default="{ row }">
        <el-tag v-if="row.current" type="success" size="small" style="margin-right: 8px">当前</el-tag>
        <el-button type="danger" size="small" @click="revoke(row)">注销</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import {reactive, ref, onMounted} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import accountService from "@/services/account.service";

interface SessionInfo {
  id: number
  username: string
  role: string
  loginIp: string
  userAgent: string
  browser: string
  os: string
  loginTime: string
  expireTime: string
  current: boolean
}

const sessions = ref<SessionInfo[]>([])

const formatTime = (t: string) => t ? new Date(t).toLocaleString() : '-'

const loadSessions = () => {
  accountService.getSessions().then((data) => {
    sessions.value = data
  })
}

const revoke = (row: SessionInfo) => {
  const tip = row.current ? '（这是当前会话，注销后将退出登录）' : ''
  ElMessageBox.confirm(`确定注销该会话？${tip}`, '提示', {type: 'warning'})
      .then(() => accountService.revokeSession(row.id))
      .then(() => {
        ElMessage.success('已注销')
        loadSessions()
      })
      .catch(() => {
      })
}

onMounted(loadSessions)

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
