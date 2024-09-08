<template>
  <div class="sites">
    <h1>Emby站点列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="sites" border style="width: 100%">
<!--      <el-table-column prop="id" label="ID" sortable width="70"/>-->
      <el-table-column prop="name" label="名称" sortable width="180"/>
      <el-table-column prop="order" label="顺序" sortable width="90"/>
      <el-table-column prop="username" label="用户名" sortable width="180"/>
      <el-table-column prop="url" label="URL地址" sortable>
        <template #default="scope">
          <a :href="scope.row.url" target="_blank">{{ scope.row.url }}</a>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="名称" label-width="140" required>
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="URL地址" label-width="140" required>
          <el-input v-model="form.url" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="用户名" label-width="140" required>
          <el-input v-model="form.username" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="密码" label-width="140" required>
          <el-input v-model="form.password" type="password" show-password autocomplete="off"/>
        </el-form-item>
        <el-form-item label="顺序" label-width="140">
          <el-input-number v-model="form.order" :min="0"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
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
import {Check, Close, Refresh} from '@element-plus/icons-vue'
import axios from "axios"

const updateAction = ref(false)
const dialogTitle = ref('')
const sites = ref([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const form = ref({
  id: 0,
  name: '',
  url: '',
  username: '',
  password: '',
  order: 0,
})

const handleAdd = () => {
  dialogTitle.value = '添加站点'
  updateAction.value = false
  form.value = {
    id: 0,
    name: '',
    url: '',
    username: '',
    password: '',
    order: 0,
  }
  formVisible.value = true
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新站点 - ' + data.name
  updateAction.value = true
  form.value = Object.assign({}, data)
  formVisible.value = true
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSite = () => {
  dialogVisible.value = false
  axios.delete('/api/emby/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/api/emby/' + form.value.id : '/api/emby'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const load = () => {
  axios.get('/api/emby').then(({data}) => {
    sites.value = data
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
</style>
