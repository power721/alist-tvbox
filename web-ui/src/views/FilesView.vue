<template>
  <div class="files">
    <h1>文件列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="files" border style="width: 100%">
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="dir" label="文件目录" width="250"/>
      <el-table-column prop="name" label="文件名称" width="180"/>
      <el-table-column prop="path" label="完整路径"/>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :fullscreen="fullscreen" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="目录" label-width="120" required>
          <el-input v-model="form.dir" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="名称" label-width="120" required>
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="内容" label-width="120" required>
          <el-input v-model="form.content" type="textarea" :rows="fullscreen?45:15"/>
          <a href="https://www.json.cn/" target="_blank">JSON验证</a>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button @click="fullscreen=!fullscreen">{{fullscreen?'缩小':'全屏'}}</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="dialogTitle" :fullscreen="true">
      <div>
        <p>配置URL：</p>
        <a :href="form.path" target="_blank">{{ form.path }}</a>
      </div>
      <h2>JSON数据</h2>
      <el-scrollbar height="800px">
        <json-viewer :value="jsonData" expanded copyable show-double-quotes :show-array-index="false" :expand-depth=5></json-viewer>
      </el-scrollbar>
      <div class="json"></div>
      <template #footer>
      <span class="dialog-footer">
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除配置文件" width="30%">
      <p>是否删除配置文件 - {{ form.name }}</p>
      <p>{{ form.path }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSub">删除</el-button>
      </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"

const updateAction = ref(false)
const dialogTitle = ref('')
const jsonData = ref({})
const files = ref([])
const detailVisible = ref(false)
const formVisible = ref(false)
const dialogVisible = ref(false)
const fullscreen = ref(false)
const form = ref({
  id: 0,
  name: '',
  dir: '/www/tvbox',
  path: '',
  content: ''
})

const handleAdd = () => {
  dialogTitle.value = '添加配置文件'
  updateAction.value = false
  form.value = {
    id: 0,
    name: '',
    dir: '/www/tvbox',
    path: '',
    content: ''
  }
  formVisible.value = true
}

const handleEdit = (file: any) => {
  axios.get('/api/files/' + file.id).then(({data}) => {
    dialogTitle.value = '更新配置文件 - ' + data.name
    updateAction.value = true
    form.value = data
    formVisible.value = true
  })
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSub = () => {
  dialogVisible.value = false
  axios.delete('/api/files/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/api/files/' + form.value.id : '/api/files'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const load = () => {
  axios.get('/api/files').then(({data}) => {
    files.value = data
  })
}

onMounted(() => {
  load()
})
</script>

<style scoped>
.space {
  margin-bottom: 6px;
}

.json pre {
  height: 600px;
  overflow: scroll;
}
</style>
