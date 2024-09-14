<template>
  <div class="list">
    <h1>网盘账号列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="accounts" border style="width: 100%">
      <el-table-column prop="id" label="ID" sortable width="70">
        <template #default="scope">
          {{ scope.row.id + 9000 }}
        </template>
      </el-table-column>
      <el-table-column prop="type" label="类型" sortable width="150">
        <template #default="scope">
          <span v-if="scope.row.type=='QUARK'">夸克网盘</span>
          <span v-else-if="scope.row.type=='UC'">UC网盘</span>
          <span v-else-if="scope.row.type=='PAN115'">115网盘</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" sortable width="200"/>
      <el-table-column label="路径">
        <template #default="scope">
          {{ fullPath(scope.row) }}
        </template>
      </el-table-column>
      <el-table-column prop="master" label="主账号？" width="120">
        <template #default="scope">
          <el-icon v-if="scope.row.master">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle" width="60%">
      <el-form :model="form">
        <el-form-item label="名称" label-width="140" required>
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="类型" label-width="120" required>
          <el-radio-group v-model="form.type" class="ml-4">
            <el-radio label="QUARK" size="large">夸克网盘</el-radio>
            <el-radio label="UC" size="large">UC网盘</el-radio>
            <el-radio label="PAN115" size="large">115网盘</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Cookie" label-width="140" required>
          <el-input v-model="form.cookie" type="textarea" :rows="5"/>
        </el-form-item>
<!--        <el-form-item label="Token" label-width="140">-->
<!--          <el-input v-model="form.token"/>-->
<!--        </el-form-item>-->
        <el-form-item label="文件夹ID" label-width="140">
          <el-input v-model="form.folder"/>
        </el-form-item>
        <el-form-item label="主账号" label-width="140">
          <el-switch
            v-model="form.master"
            inline-prompt
            active-text="是"
            inactive-text="否"
          />
          <span class="hint">主账号用来观看分享。</span>
        </el-form-item>
        <span v-if="form.name">完整路径： {{ fullPath(form) }}</span>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除网盘账号" width="30%">
      <p>是否删除网盘账号 - {{ form.id + 9000 }}</p>
      <p> {{ getTypeName(form.type) }} ： {{ form.name }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteAccount">删除</el-button>
      </span>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {Check, Close} from '@element-plus/icons-vue'
import axios from "axios"
import {ElMessage} from "element-plus";

const exp = ref(0)
const updateAction = ref(false)
const dialogTitle = ref('')
const accounts = ref([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const form = ref({
  id: 0,
  type: 'QUARK',
  name: '',
  cookie: '',
  token: '',
  folder: '',
  master: false,
})

const handleAdd = () => {
  dialogTitle.value = '添加网盘账号'
  updateAction.value = false
  form.value = {
    id: 0,
    type: 'QUARK',
    name: '',
    cookie: '',
    token: '',
    folder: '',
    master: false,
  }
  formVisible.value = true
}

const getTypeName = (type: string) => {
  if (type == 'QUARK') {
    return '夸克网盘'
  }
  if (type == 'UC') {
    return 'UC网盘'
  }
  if (type == 'PAN115') {
    return '115网盘'
  }
  return '未知'
}

const fullPath = (share: any) => {
  const path = share.name;
  if (path.startsWith('/')) {
    return path
  }
  if (share.type == 'QUARK') {
    return '/🌞我的夸克网盘/' + path
  } else if (share.type == 'UC') {
    return '/🌞我的UC网盘/' + path
  } else if (share.type == 'PAN115') {
    return '/115网盘/' + path
  } else {
    return '/网盘/' + path
  }
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新网盘账号 - ' + data.name
  updateAction.value = true
  form.value = Object.assign({}, data)
  formVisible.value = true
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteAccount = () => {
  dialogVisible.value = false
  axios.delete('/api/pan/accounts/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/api/pan/accounts/' + form.value.id : '/api/pan/accounts'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    if (accounts.value.length === 0) {
      ElMessage.success('添加成功')
    } else {
      ElMessage.success('更新成功')
    }
    load()
  })
}

const load = () => {
  axios.get('/api/pan/accounts').then(({data}) => {
    accounts.value = data
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
