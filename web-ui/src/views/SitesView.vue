<template>
  <div class="sites">
    <h1>AList站点列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="sites" border style="width: 100%">
<!--      <el-table-column prop="id" label="ID" sortable width="70"/>-->
      <el-table-column prop="name" label="名称" sortable width="180"/>
      <el-table-column prop="url" label="URL地址" sortable>
        <template #default="scope">
          <a :href="scope.row.url" target="_blank">{{ scope.row.url }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70"/>
      <el-table-column prop="folder" label="根目录" width="180"/>
      <el-table-column prop="password" label="访问密码" width="120"/>
      <el-table-column prop="xiaoya" label="小雅版？" width="90"/>
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
      <el-table-column prop="indexFile" label="索引文件">
        <template #default="scope">
          {{ scope.row.indexFile }}
          <el-button :icon="Refresh" @click="updateIndexFile(scope.row.id)"
                     v-if="scope.row.indexFile&&scope.row.indexFile.startsWith('http')">
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="order" label="顺序" sortable width="90"/>
      <el-table-column prop="disabled" label="禁用？" width="80">
        <template #default="scope">
          <el-icon v-if="scope.row.disabled">
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
          <el-button link type="primary" size="small" @click="showDetails(scope.row)">数据</el-button>
          <el-button link type="primary" size="small" @click="showIndex(scope.row)"> 索引</el-button>
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
        <el-form-item label="版本" label-width="140">
          <el-input-number v-model="form.version" min="2" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="根目录" label-width="140">
          <el-input v-model="form.folder" :readonly="form.id===1" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="访问密码" label-width="140">
          <el-input v-model="form.password" :readonly="form.id===1" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="认证Token" label-width="140">
          <el-input v-model="form.token" :readonly="form.id===1" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="可搜索？">
          <el-switch v-model="form.searchable"/>
        </el-form-item>
        <el-form-item label="小雅版">
          <el-switch v-model="form.xiaoya"/>
        </el-form-item>
        <el-form-item label="索引文件" label-width="140">
          <el-input v-model="form.indexFile" :readonly="form.id===1" placeholder="文件路径或者URL"/>
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
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="siteVisible" :title="dialogTitle" :fullscreen="true">
      <h2>文件夹列表</h2>
      <el-breadcrumb separator="/">
        <el-breadcrumb-item v-for="item of paths">
          <a @click="loadFiles(item.path)">{{ item.text }}</a>
        </el-breadcrumb-item>
      </el-breadcrumb>
      <div class="space"></div>
      <el-scrollbar>
        <div>
          <el-button
            v-for="item in jsonData.list.filter(e => e.vod_tag=='folder')"
            :key="item.vod_id"
            @click="loadFiles(item.vod_id)"
            text
          >{{ item.vod_name }}
          </el-button>
        </div>
      </el-scrollbar>
      <el-pagination v-show="total>100" layout="prev, pager, next" :page-size="100" :current-page="page" :total="total"
                     @current-change="loadData"/>
      <el-divider/>
      <h2>JSON数据</h2>
      <el-scrollbar height="600px">
        <json-viewer :value="jsonData" expanded copyable show-double-quotes :show-array-index="false"
                     :expand-depth=3></json-viewer>
      </el-scrollbar>
      <div class="json"></div>
      <template #footer>
      <span class="dialog-footer">
        <el-button type="primary" @click="siteVisible = false">关闭</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="indexVisible" title="索引数据" width="98%">
      <div>
        <div class="flex">
          <el-form-item label="索引名称">
            <el-select v-model="indexName" @change="onIndexChange">
              <el-option v-for="item in index" :key="item.name" :label="item.name" :value="item.name"/>
            </el-select>
          </el-form-item>
          <div class="flex">
            <el-button v-if="indexTotal" @click="scrapeIndex">刮削</el-button>
            <el-button type="danger" @click="deleteIndexFile">删除</el-button>
            <el-button type="primary" class="download" v-if="indexTotal" @click="downloadIndexFile">下载文件</el-button>
            <el-upload ref="upload"
                       accept=".txt"
                       :action="'/api/index-files/upload?siteId='+form.id+'&indexName='+indexName"
                       :headers="headers"
                       :limit="1"
                       :show-file-list="false"
                       :on-exceed="handleExceed"
                       :on-success="handleUploadSuccess"
                       :on-error="handleUploadError"
            >
              <template #trigger>
                <el-button type="primary">上传文件</el-button>
              </template>
              <template #tip>
                <div class="el-upload__tip text-red">
                  上传并覆盖索引文件
                </div>
              </template>
            </el-upload>
            <a href="/#/tmdb">TMDB电影数据列表</a>
          </div>
        </div>
        <el-pagination layout="prev, pager, next" :page-size="50" :current-page="indexPage" :total="indexTotal"
                       @current-change="loadIndexFile"/>
        <div v-for="line of indexContent">
          {{ line.id }}
          <el-button size="small" @click="toggleExcluded(line.id)">
            <el-icon v-if="line.path.startsWith('-')">
              <Close/>
            </el-icon>
            <el-icon v-else>
              <Check/>
            </el-icon>
          </el-button>
          : {{ line.path }}
        </div>
        <div v-if="indexCount >= 50">
          <el-pagination layout="prev, pager, next" :page-size="50" :current-page="indexPage" :total="indexTotal"
                         @current-change="loadIndexFile"/>
        </div>
      </div>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="indexVisible = false">关闭</el-button>
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
import type {UploadInstance, UploadProps, UploadRawFile} from 'element-plus'
import {ElMessage, genFileId} from "element-plus";
import type {VodList} from "@/model/VodList";
import type {Meta} from "@/model/Meta";

const upload = ref<UploadInstance>()
const headers = {
  'X-Access-Token': localStorage.getItem('token')
}

interface Item {
  path: string
  text: string
}

interface IndexLine {
  path: string
  id: number
}

const indexName = ref('custom_index')
const index = ref<Meta[]>([])
const token = ref('')
const updateAction = ref(false)
const dialogTitle = ref('')
const ids = ref('')
const page = ref(1)
const total = ref(0)
const jsonData = ref({} as VodList)
const paths = ref([] as Item[])
const sites = ref([])
const siteVisible = ref(false)
const formVisible = ref(false)
const dialogVisible = ref(false)
const indexVisible = ref(false)
const indexContent = ref<IndexLine[]>([])
const indexPage = ref(1)
const indexTotal = ref(0)
const indexCount = ref(0)
const form = ref({
  id: 0,
  name: '',
  url: '',
  folder: '',
  password: '',
  token: '',
  searchable: false,
  xiaoya: false,
  indexFile: '',
  disabled: false,
  order: 1,
  version: null,
})

const handleAdd = () => {
  dialogTitle.value = '添加站点'
  updateAction.value = false
  form.value = {
    id: 0,
    name: '',
    url: '',
    folder: '',
    password: '',
    token: '',
    searchable: false,
    xiaoya: false,
    indexFile: '',
    disabled: false,
    order: sites.value.length + 1,
    version: null,
  }
  formVisible.value = true
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新站点 - ' + data.name
  updateAction.value = true
  form.value = Object.assign({}, data)
  formVisible.value = true
}

const showDetails = (data: any) => {
  form.value = data
  dialogTitle.value = '站点数据 - ' + data.name
  loadFiles('/')
}

const loadData = (pageNumber: number) => {
  page.value = pageNumber
  loadFiles(ids.value);
}

const loadFiles = (id: string) => {
  ids.value = id
  extractPaths(id)
  if (!id.startsWith(form.value.id + '$')) {
    id = form.value.id + '$' + id
  }
  axios.get('/vod' + token.value + '?pg=' + page.value + '&t=' + id).then(({data}) => {
    jsonData.value = data
    total.value = data.total
    siteVisible.value = true
  }, ({response}) => {
    console.log(response.data.message)
    ElMessage.error('加载失败')
  })
}

const extractPaths = (id: string) => {
  const path = decodeURIComponent(id).replace(form.value.id + '$', '').split('$')[0]
  if (path == '/') {
    paths.value = [{path: '/', text: '首页'}]
    return
  }
  const array = path.split('/')
  const items: Item[] = []
  for (let index = 0; index < array.length; ++index) {
    const path = array.slice(0, index + 1).join('/')
    const text = array[index]
    items.push({text: text ? text : '首页', path: path ? path : '/'})
  }
  paths.value = items
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSite = () => {
  dialogVisible.value = false
  axios.delete('/api/sites/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/api/sites/' + form.value.id : '/api/sites'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const updateIndexFile = (id: string | number) => {
  axios.post('/api/sites/' + id + '/updateIndexFile').then(() => {
  })
}

const load = () => {
  axios.get('/api/sites').then(({data}) => {
    sites.value = data
  })
}

const showIndex = (data: any) => {
  form.value = data
  indexTotal.value = 0
  indexCount.value = 0
  loadIndexFiles()
}

const loadIndexFiles = () => {
  indexName.value = ''
  axios.get('/api/sites/' + form.value.id + '/index').then(({data}) => {
    index.value = data
    if (index.value && index.value.length > 0) {
      indexName.value = index.value[0].name
      loadIndexFile(1)
    }
  })
}

const onIndexChange = () => {
  loadIndexFile(1)
}

const loadIndexFile = (pageNumber: number) => {
  indexPage.value = pageNumber
  axios.get('/api/index-files?siteId=' + form.value.id + '&indexName=' + indexName.value + '&size=50&page=' + (pageNumber - 1)).then(({data}) => {
    indexContent.value = []
    for (let i in data.content) {
      indexContent.value.push({
        id: +i + (pageNumber - 1) * 50 + 1,
        path: data.content[i]
      })
    }
    indexTotal.value = data.totalElements
    indexCount.value = data.numberOfElements
    indexVisible.value = true
  }, () => {
    indexVisible.value = false
  })
}

const toggleExcluded = (id: number) => {
  axios.post("/api/index-files/exclude?siteId=" + form.value.id + '&indexName=' + indexName.value + '&index=' + (id - 1)).then(() => {
    loadIndexFile(indexPage.value)
  })
}

const deleteIndexFile = () => {
  axios.delete("/api/index-files?siteId=" + form.value.id + '&indexName=' + indexName.value).then(() => {
    ElMessage.success('索引文件删除成功')
    loadIndexFiles()
  })
}

const downloadIndexFile = () => {
  window.location.href = '/api/index-files/download?siteId=' + form.value.id + '&indexName=' + indexName.value + '&t=' + new Date().getTime() + '&X-ACCESS-TOKEN=' + localStorage.getItem("token");
}

const scrapeIndex = () => {
  axios.post('/api/tmdb/meta-scrape?siteId=' + form.value.id + '&indexName=' + indexName.value).then(() => {
    ElMessage.success('刮削开始')
  })
}

const handleUploadSuccess = () => {
  ElMessage.success('上传文件成功')
  upload.value!.clearFiles()
  loadIndexFile(1)
}

const handleUploadError = (error: Error) => {
  const e = JSON.parse(error.message)
  ElMessage.error('上传文件失败：' + e.detail)
  upload.value!.clearFiles()
}

const handleExceed: UploadProps['onExceed'] = (files: File[]) => {
  upload.value!.clearFiles()
  const file = files[0] as UploadRawFile
  file.uid = genFileId()
  upload.value!.handleStart(file)
}

onMounted(() => {
  load()
  axios.get('/api/token').then(({data}) => {
    token.value = data ? '/' + (data + '').split(',')[0] : ''
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

.flex {
  display: flex;
  flex-direction: row;
  justify-content: flex-start;
  gap: 0 24px;
}
</style>
