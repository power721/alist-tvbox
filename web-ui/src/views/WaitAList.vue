<script setup lang="ts">

import {computed, onMounted, ref} from "vue";
import axios from "axios";
import {onUnmounted} from "@vue/runtime-core";
import {useRouter} from "vue-router";
import {store} from "@/services/store";

const router = useRouter()

let intervalId = 0
const percentage = ref<number>(0)
const duration = computed(() => Math.floor(percentage.value / 10))

const increase = () => {
  percentage.value += 1
  if (percentage.value > 100) {
    percentage.value = 100
  }
}

const aListStarted = ref(false)

const getAListStatus = () => {
  axios.get('/alist/status').then(({data}) => {
    increase()
    store.aListStatus = data
    aListStarted.value = data != 0
    if (data !== 1) {
      clearInterval(intervalId)
      intervalId = 0
      window.location.reload()
    } else if (!intervalId) {
      percentage.value = 0
      intervalId = setInterval(getAListStatus, 1000)
    }
  })
}

onMounted(() => {
  axios.get('/alist/status').then(({data}) => {
    store.aListStatus = data
    aListStarted.value = data != 0
    if (data === 1) {
      percentage.value = 0
      intervalId = setInterval(getAListStatus, 1000)
    } else {
      router.push('/')
    }
  })
})

onUnmounted(() => {
  clearInterval(intervalId)
})
</script>

<template>
  <div>
    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>AList服务启动中</span>
        </div>
      </template>
      <el-switch
        v-model="aListStarted"
        inline-prompt
        :disabled="true"
        :active-text="store.aListStatus===2?'运行中':'启动中'"
        inactive-text="停止中"
      />
      <el-progress
        :percentage="percentage"
        :stroke-width="15"
        status="success"
        striped
        :duration="duration"
        v-if="intervalId"
      />
      <el-text class="mx-1" type="warning">
        部分功能不可用，请等待AList启动完成。
      </el-text>
    </el-card>
  </div>
</template>

<style scoped>

</style>
