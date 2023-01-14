<script setup lang="ts">
const currentUrl = window.location.origin
</script>

<template>
  <div>
    <h1>AList - TvBox</h1>

    <h2>
      TvBox配置URL：
    </h2>
    <p>只包含AList数据源</p>
    <p>
      {{ currentUrl }}/sub/0
    </p>

    <h2>
      TvBox配置URL：
    </h2>
    <p>
      {{ currentUrl }}/sub/1
    </p>
  </div>
</template>
