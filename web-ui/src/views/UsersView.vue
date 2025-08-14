<template>
  <div class="files">
    <h1>用户列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="users" border style="width: 100%">
      <el-table-column prop="id" label="ID"/>
      <el-table-column prop="username" label="用户名"/>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle">
      <el-form :model="form" label-width="120">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="密码">
          <el-input type="password" v-model="form.password" autocomplete="off"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除用户" width="30%">
      <p>是否删除用户 - {{ form.id }}</p>
      <p>{{ form.username }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteUser">删除</el-button>
      </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"

interface User {
  id: number
  username: string
  password: string
}

const updateAction = ref(false)
const dialogTitle = ref('')
const users = ref<User[]>([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const form = ref<User>({
  id: 0,
  username: '',
  password: '',
})

const handleAdd = () => {
  dialogTitle.value = '添加用户'
  updateAction.value = false
  form.value = {
    id: 0,
    username: '',
    password: '',
  }
  formVisible.value = true
}

const handleEdit = (file: User) => {
  dialogTitle.value = '更新用户 - ' + file.id
  updateAction.value = true
  form.value = Object.assign({}, file)
  formVisible.value = true
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteUser = () => {
  dialogVisible.value = false
  axios.delete('/api/users/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/api/users/' + form.value.id : '/api/users'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const load = () => {
  axios.get('/api/users').then(({data}) => {
    users.value = data
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
