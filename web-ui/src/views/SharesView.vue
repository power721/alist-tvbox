<template>
  <h2>资源列表</h2>
  <el-row justify="end">
    <el-input style="width: 200px;" v-model="keyword" @keyup="search">
      <template #append>
        <el-button :icon="Search" @click="search"/>
      </template>
    </el-input>
    <div class="hint"></div>
    <el-select style="width: 90px" v-model="type" @change="filter">
      <el-option
        v-for="item in options"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>
    <div class="hint"></div>
    <el-button type="success" @click="showUpload">导入</el-button>
    <el-button type="success" @click="exportVisible=true">导出</el-button>
    <!--    <el-button type="success" @click="reload" title="点击获取最新地址">Tacit0924</el-button>-->
    <el-button @click="refreshShares">刷新</el-button>
    <el-button type="primary" @click="handleAdd">添加</el-button>
    <el-button type="danger" @click="handleDeleteBatch" v-if="multipleSelection.length">删除</el-button>
  </el-row>

  <el-table :data="shares" border @selection-change="handleSelectionChange" style="width: 100%">
    <el-table-column type="selection" width="55"/>
    <el-table-column prop="id" label="ID" width="70" sortable/>
    <el-table-column prop="path" label="路径" sortable>
      <template #default="scope">
        <router-link :to="'/vod' + scope.row.path">
          {{ scope.row.path }}
        </router-link>
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
        <a v-else-if="scope.row.type==5" :href="getShareLink(scope.row)" target="_blank">
          https://pan.quark.cn/s/{{ scope.row.shareId }}
        </a>
        <a v-else-if="scope.row.type==7" :href="getShareLink(scope.row)" target="_blank">
          https://fast.uc.cn/s/{{ scope.row.shareId }}
        </a>
        <a v-else-if="scope.row.type==8" :href="getShareLink(scope.row)" target="_blank">
          https://115.com/s/{{ scope.row.shareId }}
        </a>
        <a v-else-if="scope.row.type==9" :href="getShareLink(scope.row)" target="_blank">
          https://cloud.189.cn/t/{{ scope.row.shareId }}
        </a>
        <a v-else-if="scope.row.type==6" :href="getShareLink(scope.row)" target="_blank">
          https://caiyun.139.com/m/i?{{ scope.row.shareId }}
        </a>
        <a v-else-if="scope.row.type==2" :href="getShareLink(scope.row)" target="_blank">
          https://pan.xunlei.com/s/{{ scope.row.shareId }}
        </a>
        <a v-else-if="scope.row.type==3" :href="getShareLink(scope.row)" target="_blank">
          https://www.123pan.com/s/{{ scope.row.shareId }}
        </a>
        <a v-else-if="scope.row.type==10" :href="getShareLink(scope.row)" target="_blank">
          https://pan.baidu.com/s/{{ scope.row.shareId }}
        </a>
      </template>
    </el-table-column>
    <el-table-column prop="password" label="密码" width="120"/>
    <el-table-column prop="type" label="类型" width="120" sortable>
      <template #default="scope">
        <span v-if="scope.row.type==1">PikPak分享</span>
        <span v-else-if="scope.row.type==4">本地存储</span>
        <span v-else-if="scope.row.type==5">夸克分享</span>
        <span v-else-if="scope.row.type==7">UC分享</span>
        <span v-else-if="scope.row.type==8">115分享</span>
        <span v-else-if="scope.row.type==9">天翼分享</span>
        <span v-else-if="scope.row.type==6">移动分享</span>
        <span v-else-if="scope.row.type==2">迅雷分享</span>
        <span v-else-if="scope.row.type==3">123分享</span>
        <span v-else-if="scope.row.type==10">百度分享</span>
        <span v-else>阿里分享</span>
      </template>
    </el-table-column>
    <el-table-column fixed="right" label="操作" width="120">
      <template #default="scope">
        <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
        <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  <div>
    <el-pagination layout="total, prev, pager, next, jumper, sizes" :current-page="page" :page-size="size"
                   :total="total"
                   @current-change="loadShares" @size-change="handleSizeChange"/>
  </div>

  <div class="space"></div>
  <h2>失败资源</h2>
  <el-row justify="end">
    <el-popconfirm @confirm="cleanStorages" title="是否删除全部失效资源？">
      <template #reference>
        <el-button type="danger">清理</el-button>
      </template>
    </el-popconfirm>
    <el-popconfirm @confirm="validateStorages" title="是否校验全部资源？">
      <template #reference>
        <el-button>校验</el-button>
      </template>
    </el-popconfirm>
    <el-button @click="refreshStorages">刷新</el-button>
    <el-button type="danger" @click="dialogVisible1=true" v-if="selectedStorages.length">删除</el-button>
  </el-row>
  <el-table :data="storages" border @selection-change="handleSelectionStorages" style="width: 100%">
    <el-table-column type="selection" width="55"/>
    <el-table-column prop="id" label="ID" width="70"/>
    <el-table-column prop="mount_path" label="路径"/>
    <el-table-column prop="status" label="状态" width="260">
      <template #default="scope">
        <div v-html="scope.row.status"></div>
      </template>
    </el-table-column>
    <el-table-column prop="driver" label="类型" width="120">
      <template #default="scope">
        <span v-if="scope.row.driver=='AliyunShare'">阿里分享</span>
        <span v-else-if="scope.row.driver=='PikPakShare'">PikPak分享</span>
        <span v-else-if="scope.row.driver=='QuarkShare'">夸克分享</span>
        <span v-else-if="scope.row.driver=='UCShare'">UC分享</span>
        <span v-else-if="scope.row.driver=='115 Share'">115分享</span>
        <span v-else-if="scope.row.driver=='189Share'">天翼分享</span>
        <span v-else-if="scope.row.driver=='Yun139Share'">移动分享</span>
        <span v-else-if="scope.row.driver=='ThunderShare'">迅雷分享</span>
        <span v-else-if="scope.row.driver=='123PanShare'">123分享</span>
        <span v-else-if="scope.row.driver=='BaiduShare'">百度分享</span>
        <span v-else-if="scope.row.driver=='Local'">本地存储</span>
        <span v-else-if="scope.row.driver=='Alias'">别名</span>
        <span v-else>{{ scope.row.driver }}</span>
      </template>
    </el-table-column>
    <el-table-column fixed="right" label="操作" width="130">
      <template #default="scope">
        <el-button link type="primary" size="small" @click="reloadStorage(scope.row.id)">重新加载</el-button>
        <el-button link type="danger" size="small" @click="handleDeleteStorage(scope.row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  <div>
    <el-pagination layout="total, prev, pager, next, jumper, sizes" :current-page="page1" :total="total1"
                   :page-size="size1"
                   @current-change="loadStorages" @size-change="handleSize1Change"/>
  </div>

  <el-dialog v-model="formVisible" width="60%" :title="dialogTitle">
    <el-form :model="form">
      <el-form-item label="挂载路径" label-width="140" required>
        <el-input v-model="form.path" autocomplete="off"/>
      </el-form-item>
      <el-form-item v-if="form.type!=4" label="分享ID" label-width="140" required>
        <el-input v-model="form.shareId" autocomplete="off" placeholder="分享ID或者分享链接"/>
      </el-form-item>
      <el-form-item v-if="form.type!=4" label="提取码" label-width="140">
        <el-input v-model="form.password" autocomplete="off"/>
      </el-form-item>
      <el-form-item :label="form.type==4?'本地路径':'文件夹ID'" label-width="140">
        <el-input v-model="form.folderId" autocomplete="off"
                  :placeholder="form.type==4?'':'默认为根目录或者从分享链接读取'"/>
      </el-form-item>
      <el-form-item label="类型" label-width="140">
        <el-radio-group v-model="form.type" class="ml-4">
          <el-radio :label="0" size="large">阿里分享</el-radio>
          <el-radio :label="1" size="large">PikPak分享</el-radio>
          <el-radio :label="5" size="large">夸克分享</el-radio>
          <el-radio :label="7" size="large">UC分享</el-radio>
          <el-radio :label="8" size="large">115分享</el-radio>
          <el-radio :label="9" size="large">天翼分享</el-radio>
          <el-radio :label="6" size="large">移动分享</el-radio>
          <el-radio :label="2" size="large">迅雷分享</el-radio>
          <el-radio :label="3" size="large">123分享</el-radio>
          <el-radio :label="10" size="large">百度分享</el-radio>
          <el-radio :label="4" size="large">本地存储</el-radio>
        </el-radio-group>
      </el-form-item>
      <span v-if="form.path">完整路径： {{ fullPath(form) }}</span>
      <div>网盘帐号在帐号页面添加。</div>
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

  <el-dialog v-model="dialogVisible1" title="删除资源" width="30%">
    <div v-if="selectedStorages.length">
      <p>是否删除选中的{{ selectedStorages.length }}个资源?</p>
    </div>
    <div v-else>
      <p>是否删除资源 - {{ storage.id }}</p>
      <p>{{ storage.mount_path }}</p>
    </div>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible1 = false">取消</el-button>
        <el-button type="danger" @click="deleteStorage">删除</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="uploadVisible" title="导入分享" width="60%">
    <el-form label-width="140">
      <el-form-item label="类型">
        <el-radio-group v-model="sharesDto.type" class="ml-4">
          <el-radio :label="-1" size="large">自动</el-radio>
          <el-radio :label="0" size="large">阿里分享</el-radio>
          <el-radio :label="1" size="large">PikPak分享</el-radio>
          <el-radio :label="5" size="large">夸克分享</el-radio>
          <el-radio :label="7" size="large">UC分享</el-radio>
          <el-radio :label="8" size="large">115分享</el-radio>
          <el-radio :label="9" size="large">天翼分享</el-radio>
          <el-radio :label="6" size="large">移动分享</el-radio>
          <el-radio :label="2" size="large">迅雷分享</el-radio>
          <el-radio :label="3" size="large">123分享</el-radio>
          <el-radio :label="10" size="large">百度分享</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="分享内容">
        <el-input v-model="sharesDto.content" type="textarea" :rows="15"
                  :placeholder="'多行分享\n格式1：挂载路径 分享ID 目录ID 提取码\n格式2：挂载路径 分享链接\n格式3：挂载路径 分享链接 root 提取码'"/>
      </el-form-item>
      <el-form-item label="导入文件">
        <el-upload
          ref="upload"
          action="/api/import-share-file"
          accept="text/plain"
          class="upload"
          :limit="1"
          :on-exceed="handleExceed"
          :on-success="onUploadSuccess"
          :on-error="onUploadError"
          :headers="{'x-access-token': token}"
          :data="{type: sharesDto.type}"
          :auto-upload="false"
        >
          <template #trigger>
            <el-button type="primary" :disabled="uploading">选择文件</el-button>
          </template>
          <span class="hint"></span>
          <el-button type="success" :disabled="uploading" @click="submitUpload">
            上传导入
          </el-button>
          <template #tip>
            <div class="el-upload__tip">
              上传分享列表文件，最大20MB
            </div>
          </template>
        </el-upload>
      </el-form-item>
      <el-progress v-if="uploading" :percentage="100" status="success" :indeterminate="true" :duration="5"/>
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button class="ml-3" type="success" :disabled="uploading" @click="importShares">导入</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="exportVisible" title="导出分享" width="60%">
    <el-form-item label="类型" label-width="140">
      <el-radio-group v-model="form.type" class="ml-4">
        <el-radio :label="-1" size="large">全部</el-radio>
        <el-radio :label="0" size="large">阿里分享</el-radio>
        <el-radio :label="1" size="large">PikPak分享</el-radio>
        <el-radio :label="5" size="large">夸克分享</el-radio>
        <el-radio :label="7" size="large">UC分享</el-radio>
        <el-radio :label="8" size="large">115分享</el-radio>
        <el-radio :label="9" size="large">天翼分享</el-radio>
        <el-radio :label="6" size="large">移动分享</el-radio>
        <el-radio :label="2" size="large">迅雷分享</el-radio>
        <el-radio :label="3" size="large">123分享</el-radio>
        <el-radio :label="10" size="large">百度分享</el-radio>
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
import { genFileId } from 'element-plus'
import type { UploadInstance, UploadProps, UploadRawFile } from 'element-plus'
const upload = ref<UploadInstance>()
import accountService from "@/services/account.service";
import {Search} from "@element-plus/icons-vue";

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

const options = [
  {label: '全部', value: -1},
  {label: '夸克', value: 5},
  {label: 'UC', value: 7},
  {label: '阿里', value: 0},
  {label: '115', value: 8},
  {label: '123', value: 3},
  {label: '天翼', value: 9},
  {label: '百度', value: 10},
  {label: '迅雷', value: 2},
  {label: '移动', value: 6},
  {label: 'PikPak', value: 1},
  {label: '本地', value: 4},
]

const multipleSelection = ref<ShareInfo[]>([])
const storages = ref<Storage[]>([])
const selectedStorages = ref<Storage[]>([])
const storage = ref<Storage>({
  id: 0,
  mount_path: '',
  driver: '',
  status: '',
  addition: ''
})
const page = ref(1)
const page1 = ref(1)
const size = ref(20)
const type = ref(-1)
const size1 = ref(20)
const total = ref(0)
const total1 = ref(0)
const shares = ref([])
const keyword = ref('')
const dialogTitle = ref('')
const formVisible = ref(false)
const uploadVisible = ref(false)
const uploading = ref(false)
const exportVisible = ref(false)
const dialogVisible = ref(false)
const dialogVisible1 = ref(false)
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
  type: -1
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

const handleDeleteStorage = (data: any) => {
  storage.value = data
  dialogVisible1.value = true
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

const deleteStorage = () => {
  dialogVisible1.value = false
  if (selectedStorages.value.length) {
    axios.post('/api/delete-shares', selectedStorages.value.map(s => s.id)).then(() => {
      loadStorages(page1.value)
    })
  } else {
    axios.delete('/api/shares/' + storage.value.id).then(() => {
      loadStorages(page1.value)
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
  } else if (share.type == 5) {
    return '/我的夸克分享/' + path
  } else if (share.type == 7) {
    return '/我的UC分享/' + path
  } else if (share.type == 8) {
    return '/我的115分享/' + path
  } else if (share.type == 9) {
    return '/我的天翼分享/' + path
  } else if (share.type == 6) {
    return '/我的移动分享/' + path
  } else if (share.type == 2) {
    return '/我的迅雷分享/' + path
  } else if (share.type == 3) {
    return '/我的123分享/' + path
  } else if (share.type == 10) {
    return '/我的百度分享/' + path
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
  let url = ''
  if (shareInfo.type == 1) {
    url = 'https://mypikpak.com/s/' + shareInfo.shareId
  } else if (shareInfo.type == 5) {
    url = 'https://pan.quark.cn/s/' + shareInfo.shareId
  } else if (shareInfo.type == 7) {
    url = 'https://fast.uc.cn/s/' + shareInfo.shareId
  } else if (shareInfo.type == 8) {
    url = 'https://115.com/s/' + shareInfo.shareId
  } else if (shareInfo.type == 9) {
    url = 'https://cloud.189.cn/t/' + shareInfo.shareId
  } else if (shareInfo.type == 6) {
    url = 'https://caiyun.139.com/m/i?' + shareInfo.shareId
  } else if (shareInfo.type == 2) {
    url = 'https://pan.xunlei.com/s/' + shareInfo.shareId
  } else if (shareInfo.type == 3) {
    url = 'https://www.123pan.com/s/' + shareInfo.shareId
  } else if (shareInfo.type == 10) {
    url = 'https://pan.baidu.com/s/' + shareInfo.shareId
  } else {
    url = 'https://www.alipan.com/s/' + shareInfo.shareId
    if (shareInfo.folderId) {
      url = url + '/folder/' + shareInfo.folderId
    }
  }
  if (shareInfo.password) {
    if (shareInfo.type == 1 || shareInfo.type == 2 || shareInfo.type == 10) {
      url = url + '?pwd=' + shareInfo.password
    } else {
      url = url + '?password=' + shareInfo.password
    }
  }
  return url
}

const filter = () => {
  loadShares(1)
}

const search = () => {
  loadShares(1)
}

const loadShares = (value: number) => {
  page.value = value
  axios.get('/api/shares?page=' + (page.value - 1) + '&size=' + size.value + '&type=' + type.value + '&keyword=' + keyword.value).then(({data}) => {
    shares.value = data.content
    total.value = data.totalElements
  })
}

const loadStorages = (value: number) => {
  page1.value = value
  axios.get('/api/storages?page=' + page1.value + '&size=' + size1.value).then(({data}) => {
    storages.value = data.data.content
    total1.value = data.data.total
  })
}

const cleanStorages = () => {
  axios.delete('/api/storages').then(({data}) => {
    ElMessage.success(`删除${data}个失效资源`)
    loadStorages(1)
  })
}

const validateStorages = () => {
  axios.post('/api/storages').then(() => {
    ElMessage.success('开始校验')
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

const refreshShares = () => {
  loadShares(page.value)
}

const refreshStorages = () => {
  loadStorages(page1.value)
}

const handleSizeChange = (value: number) => {
  size.value = value
  page.value = 1
  axios.get('/api/shares?page=' + (page.value - 1) + '&size=' + size.value + '&type=' + type.value).then(({data}) => {
    shares.value = data.content
    total.value = data.totalElements
  })
}

const handleSize1Change = (value: number) => {
  size1.value = value
  loadStorages(1)
}

const reload = () => {
  axios.post('/api/tacit0924').then(() => {
    ElMessage.success('更新成功')
    loadShares(page.value)
  })
}

const showUpload = () => {
  uploadVisible.value = true
  if (upload.value) {
    upload.value!.clearFiles()
  }
}

const handleExceed: UploadProps['onExceed'] = (files) => {
  upload.value!.clearFiles()
  const file = files[0] as UploadRawFile
  file.uid = genFileId()
  upload.value!.handleStart(file)
}

const onUploadSuccess: UploadProps['onSuccess'] = (data) => {
  uploadSuccess(data)
}

const onUploadError: UploadProps['onError'] = (err) => {
  uploadError(err)
}

const submitUpload = () => {
  uploading.value = true
  upload.value!.submit()
}

const importShares = () => {
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

const handleSelectionStorages = (val: Storage[]) => {
  selectedStorages.value = val
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

.upload {
  width: 50%;
}
</style>
