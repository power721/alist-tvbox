<template>
  <div class="subscriptions">
    <h1>订阅列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="subscriptions" border style="width: 100%">
      <!--      <el-table-column prop="id" label="ID" sortable width="70"/>-->
      <el-table-column prop="sid" label="订阅ID" sortable width="180"/>
      <el-table-column prop="name" label="名称" sortable width="180"/>
      <el-table-column prop="url" label="原始配置URL" sortable>
        <template #default="scope">
          <a :href="scope.row.url" target="_blank">{{ scope.row.url }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="url" label="TvBox配置地址" sortable>
        <template #default="scope">
          <a :href="currentUrl+'/sub'+token+'/'+scope.row.sid" target="_blank">{{ currentUrl }}/sub{{
              token
            }}/{{ scope.row.sid }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="url" label="多仓聚合地址" sortable>
        <template #default="scope">
          <a :href="currentUrl+'/repo'+token+'/'+scope.row.sid" target="_blank">{{ currentUrl }}/repo{{
              token
            }}/{{ scope.row.sid }}</a>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="handleEdit(scope.row)" v-if="scope.row.id">编辑
          </el-button>
          <el-button link type="primary" size="small" @click="showDetails(scope.row)">数据
          </el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)" v-if="scope.row.id">删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-row>
      猫影视配置接口：
      <a
        :href="currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')+'/open'+token"
        target="_blank">
        {{ currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@') }}/open{{ token }}
      </a>
    </el-row>
    <el-row>
      猫影视node配置接口：
      <a
        :href="currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')+'/node'+(token ? token : '/-')+'/index.config.js'"
        target="_blank">
        {{ currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@') }}/node{{ token ? token : '/-' }}/index.js.md5
      </a>
    </el-row>
    <el-row>
      <el-button @click="syncCat">同步文件</el-button>
    </el-row>

    <el-dialog v-model="formVisible" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="订阅ID" label-width="140" required>
          <el-input v-model="form.sid" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="名称" label-width="140" required>
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="配置URL" label-width="140">
          <el-input v-model="form.url" autocomplete="off" placeholder="支持多个，逗号分割。留空使用默认配置。"/>
        </el-form-item>
        <el-form-item label="排序字段" label-width="140">
          <el-input v-model="form.sort" autocomplete="off" placeholder="留空保持默认排序"/>
        </el-form-item>
        <el-form-item label="定制" label-width="140">
          <el-input v-model="form.override" type="textarea" rows="15"/>
          <a href="https://www.json.cn/" target="_blank">JSON验证</a>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="dialogTitle" :fullscreen="true">
      <div>
        <p>配置URL：</p>
        <a :href="form.url" target="_blank">{{ form.url }}</a>
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

    <el-dialog v-model="dialogVisible" title="删除订阅" width="30%">
      <p>是否删除订阅 - {{ form.name }}</p>
      <p>{{ form.url }}</p>
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
import {ElMessage} from "element-plus";

const currentUrl = window.location.origin
const token = ref('')
const updateAction = ref(false)
const dialogTitle = ref('')
const jsonData = ref({})
const subscriptions = ref([])
const detailVisible = ref(false)
const formVisible = ref(false)
const dialogVisible = ref(false)
const form = ref({
  id: 0,
  sid: '',
  name: '',
  url: '',
  sort: '',
  override: ''
})

const handleAdd = () => {
  dialogTitle.value = '添加订阅'
  updateAction.value = false
  form.value = {
    id: 0,
    sid: '',
    name: '',
    url: '',
    sort: '',
    override: ''
  }
  formVisible.value = true
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新订阅 - ' + data.name
  updateAction.value = true
  form.value = {
    id: data.id,
    sid: data.sid,
    name: data.name,
    url: data.url,
    sort: data.sort,
    override: data.override
  }
  formVisible.value = true
}

const showDetails = (data: any) => {
  form.value = data
  dialogTitle.value = '订阅数据 - ' + data.name
  axios.get('/sub' + token.value + '/' + data.sid).then(({data}) => {
    jsonData.value = data
    detailVisible.value = true
  })
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSub = () => {
  dialogVisible.value = false
  axios.delete('/api/subscriptions/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  axios.post('/api/subscriptions', form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const syncCat = () => {
  axios.post('/api/cat/sync').then(({data}) => {
    if (data) {
      ElMessage.warning('同步失败')
    } else {
      ElMessage.success('同步成功')
    }
  })
}

const load = () => {
  axios.get('/api/subscriptions').then(({data}) => {
    subscriptions.value = data
  })
}

onMounted(() => {
  axios.get('/api/token').then(({data}) => {
    token.value = data ? '/' + (data + '').split(',')[0] : ''
    load()
  })
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
