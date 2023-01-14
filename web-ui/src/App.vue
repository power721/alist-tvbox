<script setup lang="ts">
import {RouterView} from 'vue-router'</script>

<template>
  <div class="common-layout">
    <el-container>
      <el-header>
        <el-menu mode="horizontal" :ellipsis="false" :router="true">
          <el-menu-item index="/">首页</el-menu-item>
          <el-menu-item index="/sites">站点</el-menu-item>
          <el-menu-item index="/vod">vod</el-menu-item>
          <el-menu-item index="/sub">订阅</el-menu-item>
<!--          <el-menu-item index="/index">索引</el-menu-item>-->
<!--          <el-menu-item index="/settings">配置</el-menu-item>-->
<!--          <el-menu-item index="/playlist">播放列表</el-menu-item>-->
          <el-menu-item index="/about">关于</el-menu-item>
          <div class="flex-grow" />
          <el-menu-item index="/user">用户</el-menu-item>
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
