<script setup lang="ts">
import {onMounted, ref} from "vue";
import axios from "axios";
import {store} from "@/services/store";

const url = ref('http://' + window.location.hostname + ':5244')
const height = ref(window.innerHeight - 175)
const width = ref(window.innerWidth - 40)

window.onresize = () => {
  height.value = window.innerHeight - 175
  width.value = window.innerWidth - 40
}

onMounted(() => {
  if (store.xiaoya) {
    axios.get('/sites/1').then(({data}) => {
      if (data.url != 'http://localhost') {
        url.value = data.url
      }
    })
  }
})
</script>

<template>
  <div>
    <h1>
      AList - TvBox
    </h1>
    <h3 v-if="store.xiaoya">小雅集成版</h3>
    <h3 v-else>独立版</h3>
    <iframe v-if="store.xiaoya&&store.aListStatus" :src="url" :width="width" :height="height">
    </iframe>
  </div>
</template>
