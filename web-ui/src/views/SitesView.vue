<template>
  <div class="sites">
    <h1>站点列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="sites" border style="width: 100%">
      <el-table-column prop="id" label="ID" sortable width="70"/>
      <el-table-column prop="name" label="名称" sortable width="180"/>
      <el-table-column prop="url" label="URL地址" sortable/>
      <el-table-column prop="searchable" label="可搜索？" width="90">
        <template #default="scope">
          <el-icon v-if="scope.row.searchable">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="indexFile" label="索引文件"/>
      <el-table-column prop="order" label="顺序" sortable width="90"/>
      <el-table-column prop="disabled" label="禁用？" width="80">
        <template #default="scope">
          <el-icon v-if="scope.row.disabled">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="150">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="handleIndex(scope.row)">索引</el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="名称" label-width="140">
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="URL地址" label-width="140">
          <el-input v-model="form.url" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="可搜索？">
          <el-switch v-model="form.searchable"/>
        </el-form-item>
        <el-form-item label="索引文件" label-width="140">
          <el-input v-model="form.indexFile" placeholder="文件路径或者URL"/>
        </el-form-item>
        <el-form-item label="顺序" label-width="140">
          <el-input-number v-model="form.order" :min="0"/>
        </el-form-item>
        <el-form-item label="禁用？">
          <el-switch v-model="form.disabled"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="indexVisible" :title="dialogTitle">
      <el-form :model="indexRequest">
        <el-form-item label="索引名称" label-width="140">
          <span>用于生成文件名，自动添加后缀.txt</span>
          <el-input v-model="indexRequest.indexName" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="索引路径" label-width="140">
          <span>支持多个路径，逗号分割</span>
          <el-input v-model="indexRequest.paths" autocomplete="off" placeholder="逗号分割"/>
        </el-form-item>
        <el-form-item label="排除路径" label-width="140">
          <span>支持多个路径，逗号分割</span>
          <el-input v-model="indexRequest.excludes" autocomplete="off" placeholder="逗号分割"/>
        </el-form-item>
        <el-form-item label="排除关键词" label-width="140">
          <span>支持多个关键词，逗号分割</span>
          <el-input v-model="indexRequest.stopWords" autocomplete="off" placeholder="逗号分割"/>
        </el-form-item>
        <el-form-item label="包含外部AList站点？">
          <el-switch v-model="indexRequest.excludeExternal"/>
        </el-form-item>
        <el-form-item label="压缩文件？">
          <el-switch v-model="indexRequest.compress"/>
        </el-form-item>
        <el-form-item label="最大索引目录层级" label-width="140">
          <el-input-number v-model="indexRequest.maxDepth" :min="1"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="indexVisible=false">取消</el-button>
        <el-button type="primary" @click="handleIndexRequest">索引</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除站点" width="30%">
      <p>是否删除站点 - {{ form.name }}</p>
      <p>{{ form.url }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSite">删除</el-button>
      </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {Check, Close} from '@element-plus/icons-vue'
import axios from "axios"
import {ElMessage, ElNotification} from "element-plus";

const updateAction = ref(false)
const dialogTitle = ref('')
const sites = ref([])
const indexVisible = ref(false)
const formVisible = ref(false)
const dialogVisible = ref(false)
const form = ref({
  id: '',
  name: '',
  url: '',
  searchable: false,
  indexFile: '',
  disabled: false,
  order: 0,
})
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

const handleAdd = () => {
  dialogTitle.value = '添加站点'
  updateAction.value = false
  form.value = {
    id: '',
    name: '',
    url: '',
    searchable: false,
    indexFile: '',
    disabled: false,
    order: 0,
  }
  formVisible.value = true
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新站点 - ' + data.name
  updateAction.value = true
  form.value = data
  formVisible.value = true
}

const handleIndex = (data: any) => {
  dialogTitle.value = '索引站点 - ' + data.name
  indexRequest.value = {
    siteId: data.id,
    indexName: 'index',
    excludeExternal: false,
    compress: false,
    maxDepth: 10,
    paths: '/',
    stopWords: '',
    excludes: '',
  }
  indexVisible.value = true
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSite = () => {
  dialogVisible.value = false
  axios.delete('/sites/' + form.value.id).then(() => {
    load()
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
  axios.post('/index', request).then(({data}) => {
    indexVisible.value = false
    ElNotification({
      title: '索引文件路径',
      message: data.filePath,
      duration: 0,
    })
  }, ({response}) => {
    ElMessage.error(response.data.message)
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/sites/' + form.value.id : '/sites'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    load()
  }, ({response}) => {
    ElMessage.error(response.data.message)
  })
}

const load = () => {
  axios.get('/sites').then(({data}) => {
    sites.value = data
  })
}

onMounted(() => {
  load()
})
</script>

<style>
.space {
  margin-bottom: 6px;
}
</style>
