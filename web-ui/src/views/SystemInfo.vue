<template>
  <div class="ui left aligned container">
    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>网络</span>
        </div>
      </template>
      <div>IP地址： {{ info.ip }}</div>
      <div>主机名： {{ info.hostName }}</div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>Java</span>
        </div>
      </template>
      <div>版本： {{ info.javaVendor }} {{ info.javaVersion }}</div>
      <div>JRE目录： {{ info.javaHome }}</div>
      <div>JVM名称： {{ info.jvmName }}</div>
      <div>JVM CPU： {{ info.jvmCpus }}核</div>
      <div>JVM 内存：
        <span data-tooltip="空闲内存">{{ byte2string(info.jvmUsedMemory)}}</span> /
        <span data-tooltip="总内存">{{ byte2string(info.jvmTotalMemory) }}</span>
      </div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>系统</span>
        </div>
      </template>
      <div>名称： {{ info.osName }}</div>
      <div>版本： {{ info.osVersion }}</div>
      <div>架构： {{ info.osArch }}</div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>用户</span>
        </div>
      </template>
      <div>用户名称： {{ info.userName }}</div>
      <div>用户目录： {{ info.userHome }}</div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>其它</span>
        </div>
      </template>
      <div>工作目录： {{ info.workDir }}</div>
      <div>时区： {{ info.timezone }}</div>
      <div>AList端口： {{ info.alistPort }}</div>
      <div>PID： {{ info.pid }}</div>
    </el-card>

  </div>
</template>L

<script setup lang="ts">
import {onMounted, ref} from "vue";
import axios from "axios";

const info = ref<any>({})
const load = () => {
  axios.get('/api/system').then(({data}) => {
    info.value = data
  })
}

const KB = 1024;
const MB = 1024 * KB;
const GB = 1024 * MB;
const TB = 1024 * GB;
const PB = 1024 * TB;
const EB = 1024 * PB;
const ZB = 1024 * EB;

const number2string = (num: number, fractionDigits = 2) => {
  let str = num.toFixed(fractionDigits);
  while (str.endsWith('0')) {
    str = str.substring(0, str.length - 1);
  }
  if (str.endsWith('.')) {
    str = str.substring(0, str.length - 1);
  }
  return str;
}
const byte2string = (bytes: number, unit = '') => {
  if (bytes >= EB || unit === 'EB') {
    return number2string(bytes / EB) + ' EB';
  } else if (bytes >= PB || unit === 'PB') {
    return number2string(bytes / PB) + ' PB';
  } else if (bytes >= TB || unit === 'TB') {
    return number2string(bytes / TB) + ' TB';
  } else if (bytes >= GB || unit === 'GB') {
    return number2string(bytes / GB) + ' GB';
  } else if (bytes >= MB || unit === 'MB') {
    return number2string(bytes / MB) + ' MB';
  } else if (bytes >= KB || unit === 'KB') {
    return number2string(bytes / KB) + ' KB';
  } else {
    return bytes + ' bytes';
  }
}

onMounted(() => {
  load()
})
</script>

<style scoped>
.box-card {
  width: 500px;
}
</style>
