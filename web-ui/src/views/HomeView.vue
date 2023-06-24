<script setup lang="ts">
import {store} from "@/services/store";
import {ref} from "vue";

const height = ref(window.innerHeight - 175)
const width = ref(window.innerWidth - 40)

window.onresize = () => {
  height.value = window.innerHeight - 175
  width.value = window.innerWidth - 40
}
</script>

<template>
  <div>
    <h1>
      AList - TvBox
    </h1>
    <h3 v-if="store.xiaoya">小雅集成版</h3>
    <h3 v-else>独立版</h3>
    <iframe v-if="store.xiaoya" src="http://127.0.0.1:5244" :width="width" :height="height">
    </iframe>
  </div>
</template>
