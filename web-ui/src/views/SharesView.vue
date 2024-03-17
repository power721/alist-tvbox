<template>
  <h2>资源列表</h2>
  <el-row justify="end">
    <el-button type="success" @click="uploadVisible=true">导入</el-button>
    <el-button type="success" @click="exportVisible=true">导出</el-button>
    <el-button type="success" @click="reload" title="点击获取最新地址">Tacit0924</el-button>
    <el-button type="primary" @click="handleAdd">添加</el-button>
    <el-button type="danger" @click="handleDeleteBatch" v-if="multipleSelection.length">删除</el-button>
  </el-row>

  <el-table :data="shares" border @selection-change="handleSelectionChange" style="width: 100%">
    <el-table-column type="selection" width="55"/>
    <el-table-column prop="id" label="ID" width="70" sortable/>
    <el-table-column prop="path" label="路径" sortable/>
    <el-table-column label="完整路径" width="380" sortable>
      <template #default="scope">
        {{ fullPath(scope.row) }}
      </template>
    </el-table-column>
    <el-table-column prop="url" label="分享链接">
      <template #default="scope">
        <a v-if="scope.row.type==1" :href="getShareLink(scope.row)" target="_blank">
          https://mypikpak.com/s/{{ scope.row.shareId }}
        </a>
        <a v-else-if="scope.row.type==0" :href="getShareLink(scope.row)" target="_blank">
          https://www.alipan.com/s/{{ scope.row.shareId }}
        </a>
      </template>
    </el-table-column>
    <el-table-column prop="password" label="密码" width="180"/>
    <el-table-column prop="type" label="类型" width="150" sortable>
      <template #default="scope">
        <span v-if="scope.row.type==1">PikPak分享</span>
        <span v-else-if="scope.row.type==2">夸克网盘</span>
        <span v-else-if="scope.row.type==3">115网盘</span>
        <span v-else-if="scope.row.type==4">本地存储</span>
        <span v-else>阿里分享</span>
      </template>
    </el-table-column>
    <el-table-column fixed="right" label="操作" width="200">
      <template #default="scope">
        <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
        <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  <div>
    <el-pagination layout="total, prev, pager, next, jumper, sizes" :current-page="page" :page-size="size" :total="total"
                   @current-change="loadShares" @size-change="handleSizeChange"/>
  </div>

  <div class="space"></div>
  <h2>失败列表</h2>
  <el-table :data="storages" border style="width: 100%">
    <el-table-column prop="id" label="ID" width="70"/>
    <el-table-column prop="mount_path" label="路径"/>
    <el-table-column prop="status" label="状态" width="260"/>
    <el-table-column prop="driver" label="类型" width="120">
      <template #default="scope">
        <span v-if="scope.row.driver=='AliyundriveShare2Open'">阿里分享</span>
        <span v-else-if="scope.row.driver=='PikPakShare'">PikPak分享</span>
        <span v-else-if="scope.row.driver=='Quark'">夸克网盘</span>
        <span v-else-if="scope.row.driver=='115 Cloud'">115网盘</span>
        <span v-else-if="scope.row.driver=='Local'">本地存储</span>
        <span v-else-if="scope.row.driver=='Alias'">别名</span>
        <span v-else>{{scope.row.driver}}</span>
      </template>
    </el-table-column>
    <el-table-column fixed="right" label="操作" width="80">
      <template #default="scope">
        <el-button link type="primary" size="small" @click="reloadStorage(scope.row.id)">重新加载</el-button>
      </template>
    </el-table-column>
  </el-table>
  <div>
    <el-pagination layout="total, prev, pager, next" :current-page="page1" :total="total1" :page-size="20"
                   @current-change="loadStorages"/>
  </div>

  <el-dialog v-model="formVisible" :title="dialogTitle">
    <el-form :model="form">
      <el-form-item label="挂载路径" label-width="140" required>
        <el-input v-model="form.path" autocomplete="off"/>
      </el-form-item>
      <el-form-item v-if="form.type!=2&&form.type!=3&&form.type!=4" label="分享ID" label-width="140" required>
        <el-input v-model="form.shareId" autocomplete="off" placeholder="分享ID或者分享链接"/>
      </el-form-item>
      <el-form-item v-if="form.type!=2&&form.type!=4" :label="form.type==3?'Token':'密码'" label-width="140">
        <el-input v-model="form.password" autocomplete="off"/>
      </el-form-item>
      <el-form-item v-if="form.type==2||form.type==3" label="Cookie" label-width="140">
        <el-input v-model="form.cookie" type="textarea" :rows="5" autocomplete="off" :placeholder="form.type==3?'Cookie或者Token必填一项':'Cookie必填'"/>
      </el-form-item>
      <el-form-item :label="form.type==4?'本地路径':'文件夹ID'" label-width="140">
        <el-input v-model="form.folderId" autocomplete="off" :placeholder="form.type==4?'':'默认为根目录或者从分享链接读取'"/>
      </el-form-item>
      <el-form-item label="类型" label-width="140">
        <el-radio-group v-model="form.type" class="ml-4">
          <el-radio :label="0" size="large">阿里分享</el-radio>
          <el-radio :label="1" size="large">PikPak分享</el-radio>
          <el-radio :label="2" size="large">夸克网盘</el-radio>
          <el-radio :label="3" size="large">115网盘</el-radio>
          <el-radio :label="4" size="large">本地存储</el-radio>
        </el-radio-group>
      </el-form-item>
      <span v-if="form.path">完整路径： {{ fullPath(form) }}</span>
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="dialogVisible" title="删除资源" width="30%">
    <div v-if="batch">
      <p>是否删除选中的{{ multipleSelection.length }}个资源?</p>
    </div>
    <div v-else>
      <p>是否删除资源 - {{ form.shareId }}</p>
      <p>{{ form.path }}</p>
    </div>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSub">删除</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="uploadVisible" title="导入分享" width="50%">
    <el-form>
      <el-form-item label="类型" label-width="140">
        <el-radio-group v-model="sharesDto.type" class="ml-4">
          <el-radio :label="0" size="large">阿里分享</el-radio>
          <el-radio :label="1" size="large">PikPak分享</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="分享内容" label-width="120">
        <el-input v-model="sharesDto.content" type="textarea" :rows="15" placeholder="多行分享，挂载路径+分享链接"/>
      </el-form-item>
      <el-progress v-if="uploading" :percentage="100" status="success" :indeterminate="true" :duration="5"/>
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button class="ml-3" type="success" :disabled="uploading" @click="submitUpload">导入</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="exportVisible" title="导出分享" width="30%">
    <el-form-item label="类型" label-width="140">
      <el-radio-group v-model="form.type" class="ml-4">
        <el-radio :label="0" size="large">阿里分享</el-radio>
        <el-radio :label="1" size="large">PikPak分享</el-radio>
      </el-radio-group>
    </el-form-item>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button class="ml-3" type="success" @click="exportShares">导出</el-button>
      </span>
    </template>
  </el-dialog>

</template>

<script setup lang="ts">
import {onMounted, ref} from "vue";
import axios from "axios";
import {ElMessage} from 'element-plus'
import accountService from "@/services/account.service";

const token = accountService.getToken()

interface ShareInfo {
  id: string
  path: string
  shareId: string
  folderId: string
  password: string
  cookie: string
  status: string
  type: number
}

interface Storage {
  id: number
  mount_path: string
  driver: string
  status: string
  addition: string
}

const multipleSelection = ref<ShareInfo[]>([])
const storages = ref<Storage[]>([])
const page = ref(1)
const page1 = ref(1)
const size = ref(20)
const total = ref(0)
const total1 = ref(0)
const shares = ref([])
const dialogTitle = ref('')
const formVisible = ref(false)
const uploadVisible = ref(false)
const uploading = ref(false)
const exportVisible = ref(false)
const dialogVisible = ref(false)
const updateAction = ref(false)
const batch = ref(false)
const form = ref({
  id: '',
  path: '',
  shareId: '',
  folderId: '',
  password: '',
  cookie: '',
  type: 0
})
const sharesDto = ref({
  content: '',
  type: 0
})

const handleAdd = () => {
  dialogTitle.value = '添加分享'
  updateAction.value = false
  form.value = {
    id: '',
    path: '',
    shareId: '',
    folderId: '',
    password: '',
    cookie: '',
    type: 0
  }
  formVisible.value = true
}

const handleEdit = (data: ShareInfo) => {
  dialogTitle.value = '更新分享 - ' + data.id
  updateAction.value = true
  form.value = {
    id: data.id,
    path: data.path,
    shareId: data.shareId,
    folderId: data.folderId,
    password: data.password,
    cookie: data.cookie,
    type: data.type
  }
  formVisible.value = true
}

const handleDelete = (data: any) => {
  batch.value = false
  form.value = data
  dialogVisible.value = true
}

const handleDeleteBatch = () => {
  batch.value = true
  dialogVisible.value = true
}

const deleteSub = () => {
  dialogVisible.value = false
  if (batch.value) {
    axios.post('/api/delete-shares', multipleSelection.value.map(s => s.id)).then(() => {
      loadShares(page.value)
    })
  } else {
    axios.delete('/api/shares/' + form.value.id).then(() => {
      loadShares(page.value)
    })
  }
}

const handleCancel = () => {
  formVisible.value = false
}

const fullPath = (share: any) => {
  const path = share.path;
  if (path.startsWith('/')) {
    return path
  }
  if (share.type == 1) {
    return '/\uD83D\uDD78\uFE0F我的PikPak分享/' + path
  } else if (share.type == 2) {
    return '/\uD83C\uDF1E我的夸克网盘/' + path
  } else if (share.type == 3) {
    return '/115网盘/' + path
  } else if (share.type == 4) {
    return path
  } else {
    return '/\uD83C\uDE34我的阿里分享/' + path
  }
}

const handleConfirm = () => {
  axios.post('/api/shares/' + form.value.id, form.value).then(() => {
    formVisible.value = false
    loadShares(page.value)
  })
}

const getShareLink = (shareInfo: ShareInfo) => {
  if (shareInfo.type == 1) {
    return 'https://mypikpak.com/s/' + shareInfo.shareId
  }
  let url = 'https://www.alipan.com/s/' + shareInfo.shareId
  if (shareInfo.folderId) {
    url = url + '/folder/' + shareInfo.folderId
  }
  if (shareInfo.password) {
    url = url + '?pwd=' + shareInfo.password
  }
  return url
}

const loadShares = (value: number) => {
  page.value = value
  axios.get('/api/shares?page=' + (page.value - 1) + '&size=' + size.value).then(({data}) => {
    shares.value = data.content
    total.value = data.totalElements
  })
}

const loadStorages = (value: number) => {
  page1.value = value
  axios.get('/api/storages?page=' + (page1.value - 1) + '&size=20').then(({data}) => {
    storages.value = data.data.content
    total1.value = data.data.total
  })
}

const reloadStorage = (id: number) => {
  axios.post('/api/storages/' + id).then(({data}) => {
    if (data.code == 200) {
      ElMessage.success('加载成功')
      loadStorages(page1.value)
    } else {
      ElMessage.error(data.message)
    }
  })
}

const handleSizeChange = (value: number) => {
  size.value = value
  page.value = 1
  axios.get('/api/shares?page=' + (page.value - 1) + '&size=' + size.value).then(({data}) => {
    shares.value = data.content
    total.value = data.totalElements
  })
}

const reload = () => {
  axios.post('/api/tacit0924').then(() => {
    ElMessage.success('更新成功')
    loadShares(page.value)
  })
}

const submitUpload = () => {
  uploading.value = true
  axios.post('/api/import-shares', sharesDto.value).then(({data}) => {
    uploadSuccess(data)
  }, (err) => {
    uploadError(err)
  })
}

const exportShares = () => {
  window.location.href = '/api/export-shares?type=' + form.value.type + '&t=' + new Date().getTime() + '&X-ACCESS-TOKEN=' + localStorage.getItem("token");
}

const uploadSuccess = (response: any) => {
  uploading.value = false
  uploadVisible.value = false
  sharesDto.value.content = ''
  loadShares(page.value)
  ElMessage.success('成功导入' + response + '个分享')
}

const uploadError = (error: Error) => {
  uploading.value = false
  ElMessage.error('导入失败：' + error)
}

const handleSelectionChange = (val: ShareInfo[]) => {
  multipleSelection.value = val
}

onMounted(() => {
  loadShares(page.value)
  loadStorages(page1.value)
})
</script>

<style scoped>
.space {
  margin: 12px 0;
}
</style>
