<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "@/services/api";

const type = ref("app");
const level = ref(localStorage.getItem("log_level") || "ALL");
const page = ref(1);
const count = ref(0);
const total = ref(0);
const logs = ref([]);
const options = [
  {
    label: "全部",
    value: "ALL",
  },
  {
    label: "ERROR",
    value: "ERROR",
  },
  {
    label: "WARN",
    value: "WARN",
  },
  {
    label: "INFO",
    value: "INFO",
  },
  {
    label: "DEBUG",
    value: "DEBUG",
  },
];

const reload = () => {
  load(page.value);
};

const onTypeChange = () => {
  localStorage.setItem("log_level", level.value);
  load(1);
};

const load = (pageNumber: number) => {
  page.value = pageNumber;
  api
    .get(
      "/api/logs?type=" +
        type.value +
        "&size=50&page=" +
        (pageNumber - 1) +
        "&level=" +
        level.value,
    )
    .then((data) => {
      logs.value = data.content;
      total.value = data.totalElements;
      count.value = data.numberOfElements;
    });
};

const download = () => {
  window.location.href =
    "/api/logs/download?t=" +
    new Date().getTime() +
    "&X-ACCESS-TOKEN=" +
    localStorage.getItem("token");
};

onMounted(() => {
  load(1);
});
</script>

<template>
  <el-form :inline="true">
    <el-form-item label="类型">
      <el-radio-group v-model="type" class="ml-4" @change="onTypeChange">
        <el-radio label="app" size="large"> 应用日志 </el-radio>
        <el-radio label="alist" size="large"> AList日志 </el-radio>
        <el-radio label="init" size="large"> 启动日志 </el-radio>
      </el-radio-group>
    </el-form-item>

    <el-form-item v-if="type == 'app'" label="级别">
      <el-select v-model="level" style="width: 90px" @change="onTypeChange">
        <el-option
          v-for="option in options"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
    </el-form-item>
  </el-form>

  <div class="flex">
    <el-pagination
      layout="prev, pager, next"
      :page-size="50"
      :current-page="page"
      :total="total"
      @current-change="load"
    />
    <div>
      <el-button type="primary" @click="reload"> 刷新 </el-button>
      <el-button type="primary" class="download" @click="download"> 下载日志 </el-button>
    </div>
  </div>
  <div v-for="(log, i) of logs" :key="i" v-html="log" />
  <div v-if="count >= 50">
    <el-pagination
      layout="prev, pager, next"
      :page-size="50"
      :current-page="page"
      :total="total"
      @current-change="load"
    />
  </div>
</template>

<style scoped>
.flex {
  display: flex;
  flex-direction: row;
  justify-content: flex-start;
  gap: 0 24px;
}
</style>
