<script setup lang="ts">
// @ts-nocheck
import {RouterView, useRoute, useRouter} from 'vue-router'
import accountService from "@/services/account.service";
import {onMounted, ref} from "vue";
import axios from "axios";
import {store} from "@/services/store";
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

const account = accountService.account
const route = useRoute()
const router = useRouter()
const show = ref(true)
const full = ref(localStorage.getItem('full_view') == 'true')
const mounted = ref(false)
const showNotification = ref(true)

const logout = () => {
  accountService.logout()
  router.push('/login')
}

const close = () => {
  localStorage.setItem('notification2', 'true')
}

const onModeChange = (value: boolean) => {
  localStorage.setItem('full_view', value + '')
}

onMounted(() => {
  showNotification.value = localStorage.getItem('notification2') != 'true'
  axios.get('/api/token').then(({data}) => {
    store.token = data.token ? data.token.split(',')[0] : '-'
    store.role = data.role
    store.admin = data.role === 'ADMIN'
  })

  axios.get("/api/profiles").then(({data}) => {
    store.xiaoya = data.includes('xiaoya')
    store.docker = data.includes('docker')
    store.standalone = data.includes('standalone')
    store.hostmode = data.includes('host')
    axios.get('/api/settings/install_mode').then(({data}) => {
      store.installMode = data.value
    })
    mounted.value = true
    if (show.value) {
      axios.get('/api/alist/status').then(({data}) => {
        store.aListStatus = data
        show.value = show.value && data != 1
        if (data === 1) {
         router.push('/wait?redirect=' + route.path)
        } else if (!store.admin && route.path === '/') {
          router.push('/vod')
        }
      })
    }
  })
})
</script>

<template>
  <div class="common-layout">
    <el-container>
      <el-header>
        <el-menu mode="horizontal" :ellipsis="false" :router="true">
          <el-menu-item index="/" v-if="store.admin">首页</el-menu-item>
          <el-menu-item index="/sites" v-if="account.authenticated&&store.admin">站点</el-menu-item>
          <el-menu-item index="/accounts" v-if="account.authenticated&&show&&store.admin">账号</el-menu-item>
          <el-menu-item index="/bilibili" v-if="account.authenticated&&full&&store.admin">BiliBili</el-menu-item>
          <el-menu-item index="/subscriptions" v-if="account.authenticated&&store.admin">订阅</el-menu-item>
          <el-menu-item index="/shares" v-if="account.authenticated&&show&&full&&store.admin">资源</el-menu-item>
          <el-menu-item index="/config" v-if="account.authenticated&&store.admin">配置</el-menu-item>
          <el-menu-item index="/acl" v-if="account.authenticated&&full&&store.admin">ACL</el-menu-item>
          <el-menu-item index="/index" v-if="account.authenticated&&show&&full&&store.admin">索引</el-menu-item>
          <el-menu-item index="/logs" v-if="account.authenticated&&store.admin">日志</el-menu-item>
          <el-menu-item index="/files" v-if="account.authenticated&&show&&full&&store.admin">文件</el-menu-item>
          <el-menu-item index="/alias" v-if="account.authenticated&&show&&full&&store.admin">别名</el-menu-item>
          <el-menu-item index="/users" v-if="account.authenticated&&show&&full&&store.admin">用户</el-menu-item>
          <el-menu-item index="/search" v-if="account.authenticated&&(full||!store.admin)">搜索</el-menu-item>
          <el-menu-item index="/vod" v-if="account.authenticated&&show&&(full||!store.admin)">播放</el-menu-item>
          <el-menu-item index="/live" v-if="account.authenticated&&(full||!store.admin)">直播</el-menu-item>
          <el-menu-item index="/about" v-if="account.authenticated&&store.admin">关于</el-menu-item>
          <div class="flex-grow"/>
          <span id="mode" v-if="account.authenticated&&store.admin">
            <el-switch v-model="full"
                       inline-prompt
                       active-text="高级模式"
                       inactive-text="简单模式"
                       style="--el-switch-on-color: #13ce66; --el-switch-off-color: #409eff; margin-top: -24px"
                       @change="onModeChange" />
          </span>
          <el-sub-menu v-if="account.authenticated">
            <template #title>{{ account.username }}</template>
            <el-menu-item index="/user">用户</el-menu-item>
            <el-menu-item index="/system" v-if="store.admin">系统</el-menu-item>
            <el-menu-item index="/logout" @click="logout">退出</el-menu-item>
          </el-sub-menu>
          <el-menu-item index="/login" v-else>登录</el-menu-item>
        </el-menu>
      </el-header>

      <el-main v-if="mounted">
        <el-config-provider :locale="zhCn">
          <RouterView/>
        </el-config-provider>
      </el-main>
    </el-container>
  </div>
</template>

<style>
#mode {
  margin-top: 30px;
  margin-left: 12px;
}
.flex-grow {
  flex-grow: 1;
}
.el-alert {
  width: 98%;
  margin: 0 20px;
}
.el-alert__content {
  width: 100%;
}
.el-alert .el-alert__close-btn {
  font-size: var(--el-alert-close-font-size);
  opacity: 1;
  position: absolute;
  top: 6px;
  right: 6px;
  cursor: pointer;
}
</style>
