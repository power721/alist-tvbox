<template>
  <div>
    <el-form :model="indexRequest">
      <el-form-item label="站点" :label-width="labelWidth">
        <el-select v-model="indexRequest.siteId">
          <el-option :label="site.name" :value="site.id" v-for="site of sites"/>
        </el-select>
      </el-form-item>
      <el-form-item label="索引名称" :label-width="labelWidth">
        <span>用于生成文件名，自动添加后缀.txt</span>
        <el-input v-model="indexRequest.indexName" autocomplete="off"/>
      </el-form-item>
      <el-form-item label="索引路径" :label-width="labelWidth">
        <span>支持多个路径，逗号分割</span>
        <el-input v-model="indexRequest.paths" autocomplete="off" placeholder="逗号分割"/>
      </el-form-item>
      <el-form-item label="排除路径" :label-width="labelWidth">
        <span>支持多个路径，逗号分割</span>
        <el-input v-model="indexRequest.excludes" autocomplete="off" placeholder="逗号分割"/>
      </el-form-item>
      <el-form-item label="排除关键词" :label-width="labelWidth">
        <span>支持多个关键词，逗号分割</span>
        <el-input v-model="indexRequest.stopWords" autocomplete="off" placeholder="逗号分割"/>
      </el-form-item>
      <el-form-item label="排除外部AList站点？" :label-width="labelWidth">
        <el-switch v-model="indexRequest.excludeExternal"/>
      </el-form-item>
      <el-form-item label="压缩文件？" :label-width="labelWidth">
        <el-switch v-model="indexRequest.compress"/>
      </el-form-item>
      <el-form-item label="最大索引目录层级" :label-width="labelWidth">
        <el-input-number v-model="indexRequest.maxDepth" :min="1"/>
      </el-form-item>
      <el-button type="primary" @click="handleIndexRequest">开始索引</el-button>
    </el-form>
    <div class="space"></div>

    <h2>任务列表</h2>
    <el-row justify="end">
      <el-button @click="loadTasks">刷新</el-button>
    </el-row>
    <el-table :data="tasks.content" border style="width: 100%">
      <el-table-column prop="id" label="ID" sortable width="70"/>
      <el-table-column prop="name" label="名称" sortable width="180"/>
      <el-table-column prop="status" label="状态" sortable width="120">
        <template #default="scope">
          <span>{{ getTaskStatus(scope.row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="result" label="结果" sortable width="120">
        <template #default="scope">
          <span>{{ getTaskResult(scope.row.result) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="summary" label="概要"/>
      <el-table-column prop="error" label="错误"/>
      <el-table-column prop="startTime" label="开始时间" sortable width="150"/>
      <el-table-column prop="endTime" label="结束时间" sortable width="150"/>
      <el-table-column fixed="right" label="操作" width="140">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="showDetails(scope.row)">数据</el-button>
          <el-button link type="danger" size="small" @click="handleCancel(scope.row)"
                     :disabled="scope.row.status==='COMPLETED'">取消
          </el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="total, prev, pager, next" v-model:current-page="currentPage"
                   @current-change="handleCurrentChange" :total="total"/>

    <el-dialog v-model="dialogVisible" :title="task.name" width="30%">
      <p>{{ task.data }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">关闭</el-button>
      </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios";
import {ElMessage} from "element-plus";
import type {Site} from "@/model/Site";
import type {TaskPage} from "@/model/Page";
import type {Task} from "@/model/Task";
import {onUnmounted} from "@vue/runtime-core";

let intervalId = 0
const labelWidth = 160
const total = ref(0)
const currentPage = ref(1)
const dialogVisible = ref(false)
const sites = ref([] as Site[])
const tasks = ref({} as TaskPage)
const task = ref({} as Task)
const indexRequest = ref({
  siteId: 0,
  indexName: 'index',
  excludeExternal: false,
  compress: false,
  maxDepth: 10,
  paths: '/',
  stopWords: '',
  excludes: '',
})

const loadSites = () => {
  axios.get('/sites').then(({data}) => {
    sites.value = data
    if (sites.value && sites.value.length > 0) {
      indexRequest.value.siteId = sites.value[0].id
    }
  })
}

const loadTasks = () => {
  axios.get('/tasks?sort=id,desc&size=10&page=' + (currentPage.value - 1)).then(({data}) => {
    tasks.value = data
    total.value = data.totalElements
  })
}

const showDetails = (data: any) => {
  task.value = data
  dialogVisible.value = true
}

const handleCurrentChange = (data: number) => {
  currentPage.value = data
  loadTasks()
}

const handleCancel = (data: any) => {
  axios.post('/tasks/' + data.id + '/cancel').then(() => {
    loadTasks()
  })
}

const handleDelete = (data: any) => {
  axios.delete('/tasks/' + data.id).then(() => {
    loadTasks()
  })
}

const handleIndexRequest = () => {
  const request = {
    siteId: indexRequest.value.siteId,
    indexName: indexRequest.value.indexName,
    excludeExternal: indexRequest.value.excludeExternal,
    compress: indexRequest.value.compress,
    maxDepth: indexRequest.value.maxDepth,
    paths: indexRequest.value.paths ? indexRequest.value.paths.split(/\s*,\s*/) : [],
    stopWords: indexRequest.value.stopWords ? indexRequest.value.stopWords.split(/\s*,\s*/) : [],
    excludes: indexRequest.value.excludes ? indexRequest.value.excludes.split(/\s*,\s*/) : [],
  }
  axios.post('/index', request).then(() => {
    currentPage.value = 1
    loadTasks()
  })
}

const getTaskStatus = (status: string) => {
  if (status === 'READY') {
    return '就绪'
  }
  if (status === 'COMPLETED') {
    return '结束'
  }
  if (status === 'RUNNING') {
    return '运行'
  }
  return ''
}

const getTaskResult = (result: string) => {
  if (result === 'OK') {
    return '成功'
  }
  if (result === 'FAILED') {
    return '失败'
  }
  if (result === 'CANCELLED') {
    return '取消'
  }
  return ''
}

onMounted(() => {
  loadSites()
  loadTasks()
  intervalId = setInterval(loadTasks, 30000)
})

onUnmounted(() => {
  clearInterval(intervalId)
})
</script>

<style scoped>
.space {
  margin-bottom: 6px;
}
</style>
