<template>
  <div>
    <el-form :model="form">
      <el-form-item label="站点" :label-width="labelWidth">
        <el-select v-model="form.siteId">
          <el-option :label="site.name" :value="site.id" v-for="site of sites"/>
        </el-select>
      </el-form-item>
<!--      <el-form-item label="索引名称" :label-width="labelWidth">-->
<!--        <span>用于生成文件名，自动添加后缀.txt</span>-->
<!--        <el-input v-model="form.indexName" autocomplete="off"/>-->
<!--      </el-form-item>-->
      <el-form-item v-for="(item, index) in form.paths" :key="item.key" :label="'索引路径'+(index+1)"
                    :label-width="labelWidth">
        <el-input v-model="item.value">
          <template #append>
            <el-button class="mt-2" @click.prevent="removePath(item)">删除</el-button>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item :label-width="labelWidth">
        <el-button @click="addPath">添加路径</el-button>
      </el-form-item>
      <el-form-item label="排除路径" :label-width="labelWidth">
        <span>支持多个路径，逗号分割</span>
        <el-input v-model="form.excludes" autocomplete="off" placeholder="逗号分割"/>
      </el-form-item>
      <el-form-item label="忽略关键词" :label-width="labelWidth">
        <span>支持多个关键词，逗号分割</span>
        <el-input v-model="form.stopWords" autocomplete="off" placeholder="逗号分割"/>
      </el-form-item>
      <el-form-item label="排除外部AList站点？" :label-width="labelWidth">
        <el-switch v-model="form.excludeExternal"/>
      </el-form-item>
      <el-form-item label="包含文件？" :label-width="labelWidth">
        <el-switch v-model="form.includeFiles"/>
      </el-form-item>
      <el-form-item label="增量更新？" :label-width="labelWidth">
        <el-switch v-model="form.incremental"/>
      </el-form-item>
      <el-form-item label="压缩文件？" :label-width="labelWidth">
        <el-switch v-model="form.compress"/>
      </el-form-item>
      <el-form-item label="限速" :label-width="labelWidth">
        <el-input-number v-model="form.sleep" :min="0"/>
        <span class="hint">毫秒</span>
      </el-form-item>
      <el-form-item label="最大索引目录层级" :label-width="labelWidth">
        <el-input-number v-model="form.maxDepth" :min="1"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleForm">开始索引</el-button>
        <el-button type="info" @click="saveTemplates">存为模板</el-button>
        <el-button type="info" @click="showTemplates">索引模板</el-button>
      </el-form-item>
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
      <el-table-column prop="startTime" label="开始时间" :formatter="datetime" sortable width="155"/>
      <el-table-column prop="endTime" label="结束时间" :formatter="datetime" sortable width="155"/>
      <el-table-column label="耗时" width="80">
        <template #default="scope">
          <div>{{formatDuration(scope.row.startTime, scope.row.endTime)}}</div>
        </template>
      </el-table-column>
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
      <pre>{{ task.data }}</pre>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">关闭</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="templatesVisible" title="索引模板">
      <el-table :data="templates" border style="width: 100%">
        <el-table-column prop="id" label="ID" sortable width="70"/>
        <el-table-column prop="name" label="名称" sortable width="120"/>
        <el-table-column prop="data" label="数据"/>
        <el-table-column prop="createdTime" label="创建时间" :formatter="datetime" sortable width="155"/>
        <el-table-column fixed="right" label="操作" width="140">
          <template #default="scope">
            <el-button link type="primary" size="small" @click="loadTemplate(scope.row)">加载</el-button>
            <el-button link type="danger" size="small" @click="deleteTemplate(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="templatesVisible = false">关闭</el-button>
      </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import axios from "axios";
import type {Site} from "@/model/Site";
import type {TaskPage} from "@/model/Page";
import type {Task} from "@/model/Task";
import {onUnmounted} from "@vue/runtime-core";
import type {IndexTemplate} from "@/model/IndexTemplate";
import {ElMessage} from "element-plus";
import {formatDatetime, formatDuration} from "@/services/utils";

interface Item {
  key: number
  value: string
}

let intervalId = 0
const labelWidth = 160
const total = ref(0)
const currentPage = ref(1)
const dialogVisible = ref(false)
const templatesVisible = ref(false)
const sites = ref([] as Site[])
const tasks = ref({} as TaskPage)
const task = ref({} as Task)
const templates = ref([] as IndexTemplate[])
const form = reactive({
  siteId: 1,
  indexName: 'custom_index',
  excludeExternal: false,
  incremental: true,
  includeFiles: false,
  compress: false,
  sleep: 2000,
  maxDepth: 10,
  paths: [{
    key: 1,
    value: '/',
  }],
  stopWords: '',
  excludes: '',
})

const datetime = (row: any, column: any, cellValue: any) => {
  if (cellValue) {
    return formatDatetime(new Date(cellValue))
  }
  return ''
}

const loadSites = () => {
  axios.get('/sites').then(({data}) => {
    sites.value = data
    if (sites.value && sites.value.length > 0) {
      form.siteId = sites.value[0].id
    }
  })
}

const loadTasks = () => {
  axios.get('/tasks?sort=id,desc&size=10&page=' + (currentPage.value - 1)).then(({data}) => {
    tasks.value = data
    total.value = data.totalElements
  })
}

const loadTemplates = () => {
  axios.get('/index-templates?sort=id,desc').then(({data}) => {
    templates.value = data.content
  })
}

const showTemplates = () => {
  loadTemplates()
  templatesVisible.value = true
}

const loadTemplate = (data: IndexTemplate) => {
  const template = JSON.parse(data.data)
  console.log(template)
  form.siteId = template.siteId
  form.indexName = template.indexName
  form.excludeExternal = template.excludeExternal
  form.includeFiles = template.includeFiles
  form.incremental = template.incremental
  form.compress = template.compress
  form.sleep = template.sleep
  form.maxDepth = template.maxDepth
  form.paths = []
  for (let path of template.paths) {
    form.paths.push({
      key: Date.now(),
      value: path,
    })
  }
  form.excludes = template.excludes.join(',')
  form.stopWords = template.stopWords.join(',')
}

const saveTemplates = () => {
  const data = {
    siteId: form.siteId,
    indexName: form.indexName,
    excludeExternal: form.excludeExternal,
    includeFiles: form.includeFiles,
    incremental: form.incremental,
    compress: form.compress,
    maxDepth: form.maxDepth,
    sleep: form.sleep,
    paths: form.paths.map(e => e.value).filter(e => e),
    stopWords: form.stopWords ? form.stopWords.split(/\s*,\s*/) : [],
    excludes: form.excludes ? form.excludes.split(/\s*,\s*/) : [],
  }
  const request = {
    name: form.indexName,
    siteId: form.siteId,
    data: JSON.stringify(data)
  }
  axios.post('/index-templates', request).then(() => {
    ElMessage.success('保持模板成功')
  })
}

const deleteTemplate = (data: any) => {
  axios.delete('/index-templates/' + data.id).then(() => {
    loadTemplates()
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

const addPath = () => {
  form.paths.push({
    key: Date.now(),
    value: '',
  })
}

const removePath = (item: Item) => {
  if (form.paths.length < 2) {
    return
  }
  const index = form.paths.indexOf(item)
  if (index !== -1) {
    form.paths.splice(index, 1)
  }
}

const handleForm = () => {
  const request = {
    siteId: form.siteId,
    indexName: form.indexName,
    excludeExternal: form.excludeExternal,
    includeFiles: form.includeFiles,
    incremental: form.incremental,
    compress: form.compress,
    sleep: form.sleep,
    maxDepth: form.maxDepth,
    paths: form.paths.map(e => e.value).filter(e => e),
    stopWords: form.stopWords ? form.stopWords.split(/\s*,\s*/) : [],
    excludes: form.excludes ? form.excludes.split(/\s*,\s*/) : [],
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
