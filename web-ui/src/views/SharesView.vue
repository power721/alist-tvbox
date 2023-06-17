<template>
  <h2>分享列表</h2>
  <el-row justify="end">
    <el-button type="primary" @click="handleAdd">添加</el-button>
  </el-row>
  <div class="space"></div>

  <el-table :data="shares" border style="width: 100%">
    <el-table-column prop="id" label="ID" width="70"/>
    <el-table-column prop="path" label="路径"/>
    <el-table-column prop="url" label="分享连接" width="350">
      <template #default="scope">
        <a :href="getShareLink(scope.row)" target="_blank">https://www.aliyundrive.com/s/{{ scope.row.shareId }}</a>
      </template>
    </el-table-column>
    <el-table-column prop="password" label="密码" width="180"/>
    <el-table-column fixed="right" label="操作" width="200">
      <template #default="scope">
        <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
        <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  <div>
    <el-pagination layout="total, prev, pager, next" :current-page="page" :page-size="size" :total="total"
                   @current-change="loadShares"/>
  </div>

  <el-dialog v-model="formVisible" :title="dialogTitle">
    <el-form :model="form">
      <el-form-item label="挂载路径" label-width="140">
        <el-input v-model="form.path" autocomplete="off"/>
      </el-form-item>
      <el-form-item label="分享ID" label-width="140">
        <el-input v-model="form.shareId" autocomplete="off"/>
      </el-form-item>
      <el-form-item label="密码" label-width="140">
        <el-input v-model="form.password" autocomplete="off"/>
      </el-form-item>
      <el-form-item label="文件夹ID" label-width="140">
        <el-input v-model="form.folderId" autocomplete="off"/>
      </el-form-item>
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="dialogVisible" title="删除分享" width="30%">
    <p>是否删除分享 - {{ form.shareId }}</p>
    <p>{{ form.path }}</p>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSub">删除</el-button>
      </span>
    </template>
  </el-dialog>

  <div class="space"></div>
  <h2>资源列表</h2>

  <el-table :data="resources" border style="width: 100%">
    <el-table-column prop="id" label="ID" width="70"/>
    <el-table-column prop="path" label="路径"/>
    <el-table-column prop="url" label="分享连接" width="350">
      <template #default="scope">
        <a :href="getShareLink(scope.row)" target="_blank">https://www.aliyundrive.com/s/{{ scope.row.shareId }}</a>
      </template>
    </el-table-column>
    <el-table-column prop="password" label="密码" width="180"/>
    <el-table-column prop="status" label="状态" width="120"/>
  </el-table>
  <div>
    <el-pagination layout="total, prev, pager, next" :current-page="page1" :page-size="size1" :total="total1"
                   @current-change="loadResource"/>
  </div>

</template>

<script setup lang="ts">
import {onMounted, ref} from "vue";
import axios from "axios";

interface ShareInfo {
  id: string
  path: string
  shareId: string
  folderId: string
  password: string
  status: string
}

const page = ref(1)
const size = ref(20)
const total = ref(0)
const page1 = ref(1)
const size1 = ref(20)
const total1 = ref(0)
const resources = ref([])
const shares = ref([])
const dialogTitle = ref('')
const formVisible = ref(false)
const dialogVisible = ref(false)
const updateAction = ref(false)
const form = ref({
  id: '',
  path: '',
  shareId: '',
  folderId: '',
  password: ''
})

const handleAdd = () => {
  dialogTitle.value = '添加分享'
  updateAction.value = false
  form.value = {
    id: '',
    path: '',
    shareId: '',
    folderId: '',
    password: ''
  }
  formVisible.value = true
}

const handleEdit = (data: ShareInfo) => {
  dialogTitle.value = '更新分享 - ' + data.shareId
  updateAction.value = true
  form.value = {
    id: data.id,
    path: data.path,
    shareId: data.shareId,
    folderId: data.folderId,
    password: data.password
  }
  formVisible.value = true
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSub = () => {
  dialogVisible.value = false
  axios.delete('/shares/' + form.value.id).then(() => {
    loadShares(page.value)
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  axios.post('/shares/' + form.value.id, form.value).then(() => {
    formVisible.value = false
    loadShares(page.value)
  })
}

const getShareLink = (shareInfo: ShareInfo) => {
  let url = 'https://www.aliyundrive.com/s/' + shareInfo.shareId
  if (shareInfo.folderId) {
    url = url + '/folder/' + shareInfo.folderId
  }
  if (shareInfo.password) {
    url = url + '?password=' + shareInfo.password
  }
  return url
}

const loadResource = (value: number) => {
  page1.value = value
  axios.get('/resources?page=' + (page1.value - 1) + '&size=' + size1.value).then(({data}) => {
    resources.value = data.content
    total1.value = data.totalElements
  })
}

const loadShares = (value: number) => {
  page.value = value
  axios.get('/shares?page=' + (page.value - 1) + '&size=' + size.value).then(({data}) => {
    shares.value = data.content
    total.value = data.totalElements
  })
}

onMounted(() => {
  loadShares(page.value)
  loadResource(page.value)
})
</script>

<style scoped>

</style>
