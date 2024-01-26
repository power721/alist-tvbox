<template>
  <div class="files">
    <h1>别名列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="files" border style="width: 100%">
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="path" label="挂载路径"/>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :fullscreen="fullscreen" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="挂载路径" label-width="120" required>
          <el-input v-model="form.path" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="内容" label-width="120" required>
          <el-input v-model="form.content" type="textarea" :rows="fullscreen?45:15" placeholder="多行路径，每行一个路径或者路径:文件夹"/>
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

    <el-dialog v-model="dialogVisible" title="删除别名" width="30%">
      <p>是否删除别名</p>
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
const files = ref([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const fullscreen = ref(false)
const form = ref({
  id: 0,
  path: '',
  content: ''
})

const handleAdd = () => {
  dialogTitle.value = '添加别名'
  updateAction.value = false
  form.value = {
    id: 0,
    path: '',
    content: ''
  }
  formVisible.value = true
}

const handleEdit = (file: any) => {
  axios.get('/api/alist/alias/' + file.id).then(({data}) => {
    dialogTitle.value = '更新别名 - ' + file.id
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
  axios.delete('/api/alist/alias/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/api/alist/alias/' + form.value.id : '/api/alist/alias'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const load = () => {
  axios.get('/api/alist/alias').then(({data}) => {
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
