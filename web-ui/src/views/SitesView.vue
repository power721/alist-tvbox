<template>
  <div class="sites">
    <h1>站点列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="sites" border style="width: 100%">
      <el-table-column prop="id" label="ID" sortable width="90"/>
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
      <el-table-column prop="searchApi" label="搜索API"/>
      <el-table-column prop="indexFile" label="索引文件"/>
      <el-table-column prop="order" label="顺序" sortable width="90"/>
      <el-table-column prop="disabled" label="禁用？" width="90">
        <template #default="scope">
          <el-icon v-if="scope.row.disabled">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="120">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
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
        <el-form-item label="搜索API" label-width="140">
          <el-input v-model="form.searchApi" placeholder="默认不填写"/>
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
        <el-button type="primary" @click="handleConfirm">更新</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog
      v-model="dialogVisible"
      title="删除站点"
      width="30%"
    >
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
import {ElMessage} from "element-plus";

const updateAction = ref(false)
const dialogTitle = ref('')
const sites = ref([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const form = ref({
  id: '',
  name: '',
  url: '',
  searchable: false,
  searchApi: '',
  indexFile: '',
  disabled: false,
  order: 0,
})

const handleAdd = () => {
  dialogTitle.value = '添加站点'
  updateAction.value = false
  form.value = {
    id: '',
    name: '',
    url: '',
    searchable: false,
    searchApi: '',
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
