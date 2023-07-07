<script setup lang="ts">

import {onMounted, ref} from "vue";
import {onUnmounted} from "@vue/runtime-core";

let source: EventSource;
const logs = ref('')

onMounted(() => {
  source = new EventSource('/logs')
  source.onmessage = function (event) {
    logs.value += event.data + '\n'
    window.scrollTo(0, document.body.scrollHeight)
  }
})

onUnmounted(() => {
  source.close()
})
</script>

<template>
  <pre>
    {{ logs }}
  </pre>
  <el-backtop :right="60" :bottom="60" />
</template>

<style scoped>
pre {
  overflow-x: auto;
  white-space: pre-wrap;
  white-space: -moz-pre-wrap;
  white-space: -pre-wrap;
  white-space: -o-pre-wrap;
  word-wrap: break-word;
}
</style>
