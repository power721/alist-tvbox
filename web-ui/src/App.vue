<script setup lang="ts">
import {RouterView, useRouter} from 'vue-router'
import accountService from "@/services/account.service";

const account = accountService.account
const router = useRouter()

const logout = () => {
  accountService.logout()
  router.push('/')
}
</script>

<template>
  <div class="common-layout">
    <el-container>
      <el-header>
        <el-menu mode="horizontal" :ellipsis="false" :router="true">
          <el-menu-item index="/">首页</el-menu-item>
          <el-menu-item index="/sites" v-if="account.authenticated">站点</el-menu-item>
          <el-menu-item index="/subscriptions" v-if="account.authenticated">订阅</el-menu-item>
          <el-menu-item index="/sub/0" v-if="account.authenticated">订阅0</el-menu-item>
          <el-menu-item index="/config" v-if="account.authenticated">配置</el-menu-item>
          <el-menu-item index="/vod" v-if="account.authenticated">vod</el-menu-item>
          <el-menu-item index="/search" v-if="account.authenticated">搜索</el-menu-item>
<!--          <el-menu-item index="/sub/1" v-if="account.authenticated">订阅1</el-menu-item>-->
          <!--          <el-menu-item index="/settings">配置</el-menu-item>-->
          <!--          <el-menu-item index="/playlist">播放列表</el-menu-item>-->
          <el-menu-item index="/about" v-if="account.authenticated">关于</el-menu-item>
          <div class="flex-grow"/>
          <el-sub-menu v-if="account.authenticated">
            <template #title>{{ account.username }}</template>
            <el-menu-item index="/user">用户</el-menu-item>
            <el-menu-item index="/logout" @click="logout">退出</el-menu-item>
          </el-sub-menu>
          <el-menu-item index="/login" v-else>登录</el-menu-item>
        </el-menu>
      </el-header>

      <el-main>
        <RouterView/>
      </el-main>
    </el-container>
  </div>
</template>

<style scoped>
.flex-grow {
  flex-grow: 1;
}
</style>
