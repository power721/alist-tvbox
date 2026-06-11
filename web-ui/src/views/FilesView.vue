<template>
  <div class="files">
    <h1>文件管理</h1>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="配置文件" name="config">
        <el-row justify="end">
          <el-button @click="loadConfig">刷新</el-button>
          <el-button type="primary" @click="handleAdd">添加</el-button>
        </el-row>
        <div class="space"></div>

        <el-table :data="files" border style="width: 100%">
          <el-table-column prop="dir" label="文件目录" width="250"/>
          <el-table-column prop="name" label="文件名称" width="180"/>
          <el-table-column prop="path" label="完整路径"/>
          <el-table-column prop="link" label="链接">
            <template #default="scope">
              <a :href="currentUrl+scope.row.path.substring(4)" target="_blank"
                 v-if="scope.row.path.startsWith('/www/')">{{currentUrl + scope.row.path.substring(4)}}</a>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="200">
            <template #default="scope">
              <el-button type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
              <el-button type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="静态文件" name="static">
        <el-row justify="space-between" align="middle">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item @click="navigateTo('')">
              <el-link type="primary">/根目录</el-link>
            </el-breadcrumb-item>
            <el-breadcrumb-item v-for="(seg, idx) in pathSegments" :key="idx"
                                @click="navigateTo(pathSegments.slice(0, idx + 1).join('/'))">
              <el-link type="primary">{{ seg }}</el-link>
            </el-breadcrumb-item>
          </el-breadcrumb>
          <div>
            <template v-if="selectedPaths.length > 0">
              <el-button type="success" @click="batchDownloadSelected">
                下载 ({{ selectedPaths.length }})
              </el-button>
              <el-button type="danger" @click="batchDeleteSelected">
                删除 ({{ selectedPaths.length }})
              </el-button>
              <el-button type="warning" @click="showMoveDialog">
                移动 ({{ selectedPaths.length }})
              </el-button>
            </template>
            <template v-else>
              <el-button @click="loadStatic">刷新</el-button>
              <el-button type="success" @click="showUploadDialog">上传文件</el-button>
              <el-button type="primary" @click="showMkdirDialog">新建文件夹</el-button>
            </template>
          </div>
        </el-row>
        <div class="space"></div>

        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px">
          <template #title>
            <p>
              <span>将壁纸图片上传到 <strong>wallpapers</strong> 文件夹，可通过 API 随机获取壁纸：</span><br>
              <a :href="currentUrl+'/wallpaper'+token" target="_blank">{{ currentUrl }}/wallpaper{{ token }}</a><br>
              订阅定制，壁纸地址填写：
              <code>WALLPAPER_API</code><br>
            </p>
          </template>
        </el-alert>

        <el-table ref="staticTableRef" :data="staticFiles" border style="width: 100%" v-loading="staticLoading"
                  @row-dblclick="handleRowDblClick" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="45"/>
          <el-table-column prop="name" label="文件名" min-width="250">
            <template #default="scope">
              <el-icon v-if="scope.row.directory" style="vertical-align: middle; margin-right: 4px">
                <Folder/>
              </el-icon>
              <el-icon v-else style="vertical-align: middle; margin-right: 4px">
                <Document/>
              </el-icon>
              <span style="cursor: pointer" @click="handleRowDblClick(scope.row)">{{ scope.row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="120">
            <template #default="scope">
              {{ scope.row.directory ? '-' : formatSize(scope.row.size) }}
            </template>
          </el-table-column>
          <el-table-column label="修改时间" width="180">
            <template #default="scope">
              {{ formatDate(scope.row.lastModified) }}
            </template>
          </el-table-column>
          <el-table-column label="外部链接" min-width="200">
            <template #default="scope">
              <a v-if="!scope.row.directory" :href="currentUrl + scope.row.url" target="_blank"
                 class="static-link">{{ currentUrl + scope.row.url }}</a>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="230">
            <template #default="scope">
              <el-button type="primary" size="small" @click="showRenameDialog(scope.row)">重命名</el-button>
              <el-button type="success" size="small" @click="downloadFile(scope.row)">下载</el-button>
              <el-button type="danger" size="small" @click="handleStaticDelete(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- Config file dialogs -->
    <el-dialog v-model="formVisible" :fullscreen="fullscreen" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="目录" label-width="120" required>
          <el-select v-model="form.dir">
            <el-option
              v-for="item in options"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
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
        <json-viewer :value="jsonData" expanded copyable show-double-quotes :show-array-index="false"
                     :expand-depth=5></json-viewer>
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

    <!-- Static file dialogs -->
    <el-dialog v-model="uploadDialogVisible" title="上传文件" width="500px">
      <el-upload
        ref="uploadRef"
        :action="uploadUrl"
        :headers="uploadHeaders"
        :data="uploadData"
        :on-success="handleUploadSuccess"
        :on-error="handleUploadError"
        :before-upload="beforeUpload"
        multiple
        drag
      >
        <el-icon style="font-size: 40px; color: #909399"><Upload/></el-icon>
        <div>拖拽文件到此处或 <em>点击上传</em></div>
      </el-upload>
      <div style="margin-top: 10px">
        <el-checkbox v-model="autoExtract" label="自动解压 ZIP 文件"/>
      </div>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mkdirDialogVisible" title="新建文件夹" width="400px">
      <el-form @submit.prevent="confirmMkdir">
        <el-form-item label="文件夹名称">
          <el-input v-model="mkdirName" placeholder="请输入文件夹名称" autofocus/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mkdirDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmMkdir">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="renameDialogVisible" title="重命名" width="400px">
      <el-form @submit.prevent="confirmRename">
        <el-form-item label="新名称">
          <el-input v-model="renameNewName" placeholder="请输入新名称" autofocus/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmRename">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="deleteDialogVisible" title="确认删除" width="400px">
      <p v-if="deleteTargets.length === 1">
        确定要删除 <strong>{{ deleteTargets[0]?.name }}</strong> 吗？
      </p>
      <p v-else>
        确定要删除选中的 <strong>{{ deleteTargets.length }}</strong> 个项目吗？
      </p>
      <p v-if="deleteTargets.some((t: any) => t.directory)" style="color: #f56c6c">
        包含文件夹，将删除文件夹及所有子文件！
      </p>
      <template #footer>
        <el-button @click="deleteDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmDelete">删除</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="moveDialogVisible" title="移动到" width="500px">
      <p>已选择 <strong>{{ selectedPaths.length }}</strong> 个项目</p>
      <div class="space"></div>
      <el-tree
        :data="dirTree"
        :props="{ label: 'name', children: 'children' }"
        node-key="path"
        highlight-current
        default-expand-all
        @node-click="handleMoveTargetClick"
      />
      <div class="space"></div>
      <p v-if="moveTargetDir !== null">
        目标目录：<strong>/{{ moveTargetDir || '(根目录)' }}</strong>
      </p>
      <template #footer>
        <el-button @click="moveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmMove" :disabled="moveTargetDir === null">确定移动</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import axios from "axios"
import {Folder, Document, Upload} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import type {UploadInstance} from 'element-plus'

const currentUrl = window.location.origin
const activeTab = ref('config')

// ========== Config files ==========
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
  dir: '/data',
  path: '',
  content: ''
})
const options = ['/data', '/www/tvbox', '/www/files', '/www/cat', '/www/pg', '/www/pg/lib', '/www/zx']

const handleAdd = () => {
  dialogTitle.value = '添加配置文件'
  updateAction.value = false
  form.value = {
    id: 0,
    name: '',
    dir: '/data',
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
    loadConfig()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/api/files/' + form.value.id : '/api/files'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    loadConfig()
  })
}

const loadConfig = () => {
  axios.get('/api/files').then(({data}) => {
    files.value = data
  })
}

// ========== Static files ==========
const staticFiles = ref<any[]>([])
const staticLoading = ref(false)
const currentStaticDir = ref('')
const uploadDialogVisible = ref(false)
const mkdirDialogVisible = ref(false)
const mkdirName = ref('')
const renameDialogVisible = ref(false)
const renameNewName = ref('')
const renameTarget = ref<any>(null)
const deleteDialogVisible = ref(false)
const deleteTargets = ref<any[]>([])
const moveDialogVisible = ref(false)
const moveTargetDir = ref<string | null>(null)
const dirTree = ref<any[]>([])
const selectedPaths = ref<string[]>([])
const selectedRows = ref<any[]>([])
const uploadRef = ref<UploadInstance>()
const staticTableRef = ref()
const token = ref('')

const pathSegments = computed(() => {
  if (!currentStaticDir.value) return []
  return currentStaticDir.value.split('/').filter(Boolean)
})

const uploadUrl = '/api/static-files/upload'

const autoExtract = ref(false)

const uploadData = computed(() => ({
  dir: currentStaticDir.value,
  extract: autoExtract.value
}))

const uploadHeaders = computed(() => {
  const token = localStorage.getItem('token')
  return token ? {'Authorization': token} : {}
})

const beforeUpload = () => {
  // Refresh data params before each upload
  return true
}

const navigateTo = (dir: string) => {
  currentStaticDir.value = dir
  loadStatic()
}

const loadStatic = () => {
  staticLoading.value = true
  selectedPaths.value = []
  selectedRows.value = []
  axios.get('/api/static-files', {params: {dir: currentStaticDir.value}}).then(({data}) => {
    staticFiles.value = data
  }).finally(() => {
    staticLoading.value = false
  })
}

const handleRowDblClick = (row: any) => {
  if (row.directory) {
    currentStaticDir.value = row.path
    loadStatic()
  }
}

const handleSelectionChange = (rows: any[]) => {
  selectedRows.value = rows
  selectedPaths.value = rows.map(r => r.path)
}

const showUploadDialog = () => {
  uploadDialogVisible.value = true
}

const handleUploadSuccess = () => {
  ElMessage.success('上传成功')
  uploadDialogVisible.value = false
  uploadRef.value?.clearFiles()
  loadStatic()
}

const handleUploadError = () => {
  ElMessage.error('上传失败')
}

const showMkdirDialog = () => {
  mkdirName.value = ''
  mkdirDialogVisible.value = true
}

const confirmMkdir = () => {
  if (!mkdirName.value.trim()) {
    ElMessage.warning('请输入文件夹名称')
    return
  }
  const path = currentStaticDir.value ? currentStaticDir.value + '/' + mkdirName.value : mkdirName.value
  axios.post('/api/static-files/mkdir', null, {params: {path}}).then(() => {
    ElMessage.success('创建成功')
    mkdirDialogVisible.value = false
    loadStatic()
  }).catch(() => {
    ElMessage.error('创建失败')
  })
}

const showRenameDialog = (row: any) => {
  renameTarget.value = row
  renameNewName.value = row.name
  renameDialogVisible.value = true
}

const confirmRename = () => {
  if (!renameNewName.value.trim()) {
    ElMessage.warning('请输入新名称')
    return
  }
  axios.post('/api/static-files/rename', null, {
    params: {path: renameTarget.value.path, newName: renameNewName.value}
  }).then(() => {
    ElMessage.success('重命名成功')
    renameDialogVisible.value = false
    loadStatic()
  }).catch(() => {
    ElMessage.error('重命名失败')
  })
}

const handleStaticDelete = (row: any) => {
  deleteTargets.value = [row]
  deleteDialogVisible.value = true
}

const batchDeleteSelected = () => {
  deleteTargets.value = [...selectedRows.value]
  deleteDialogVisible.value = true
}

const confirmDelete = () => {
  const targets = deleteTargets.value
  if (targets.length === 1) {
    axios.delete('/api/static-files', {params: {path: targets[0].path}}).then(() => {
      ElMessage.success('删除成功')
      deleteDialogVisible.value = false
      loadStatic()
    }).catch(() => {
      ElMessage.error('删除失败')
    })
  } else {
    axios.delete('/api/static-files/batch', {data: {paths: targets.map(t => t.path)}}).then(({data}) => {
      ElMessage.success(`已删除 ${data} 个项目`)
      deleteDialogVisible.value = false
      loadStatic()
    }).catch(() => {
      ElMessage.error('批量删除失败')
    })
  }
}

const downloadFile = (row: any) => {
  window.open('/api/static-files/download?path=' + encodeURIComponent(row.path), '_blank')
}

const batchDownloadSelected = () => {
  axios.post('/api/static-files/batch-download', {paths: selectedPaths.value}, {responseType: 'blob'}).then(({data}) => {
    const url = window.URL.createObjectURL(new Blob([data]))
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', 'download.zip')
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
    ElMessage.success('下载完成')
  }).catch(() => {
    ElMessage.error('批量下载失败')
  })
}

const showMoveDialog = () => {
  moveTargetDir.value = null
  dirTree.value = []
  moveDialogVisible.value = true
  loadDirTree()
}

const loadDirTree = () => {
  axios.get('/api/static-files/dirs').then(({data}) => {
    dirTree.value = data
  })
}

const handleMoveTargetClick = (node: any) => {
  moveTargetDir.value = node.path
}

const confirmMove = () => {
  if (moveTargetDir.value === null) return
  axios.post('/api/static-files/move', {
    paths: selectedPaths.value,
    targetDir: moveTargetDir.value
  }).then(({data}) => {
    ElMessage.success(`已移动 ${data} 个项目`)
    moveDialogVisible.value = false
    loadStatic()
  }).catch(() => {
    ElMessage.error('移动失败')
  })
}

const formatSize = (bytes: number) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i]
}

const formatDate = (timestamp: number) => {
  if (!timestamp) return ''
  return new Date(timestamp).toLocaleString()
}

onMounted(() => {
  axios.get('/api/token').then(({data}) => {
    token.value = data.enabledToken ? "/" + data.token.split(",")[0] : ""
  })
  loadConfig()
  loadStatic()
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

.static-link {
  word-break: break-all;
  font-size: 12px;
}
</style>
