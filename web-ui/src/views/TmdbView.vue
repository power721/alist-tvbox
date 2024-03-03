<template>
  <div class="files">
    <h1>TMDB电影数据列表</h1>
    <a href="/#/meta">豆瓣电影数据列表</a>
    <el-row justify="end">
      <el-input v-model="keyword" @change="search" class="search" autocomplete="off"/>
      <el-button type="primary" @click="search" :disabled="!keyword">
        搜索
      </el-button>
      <el-button @click="showScrapeIndex">刮削</el-button>
      <!--      <el-button @click="fixMeta">去重</el-button>-->
      <el-button @click="syncMeta">同步</el-button>
      <el-button>|</el-button>
      <el-button @click="refresh">刷新</el-button>
      <el-button type="primary" @click="addMeta">添加</el-button>
      <el-button type="danger" @click="handleDeleteBatch" v-if="multipleSelection.length">删除</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="files" border @selection-change="handleSelectionChange" style="width: 100%">
      <el-table-column type="selection" width="55"/>
      <el-table-column prop="id" label="ID" width="75"/>
      <el-table-column prop="name" label="名称" width="250"/>
      <el-table-column prop="name" label="类型" width="100">
        <template #default="scope">
          {{ scope.row.type == 'tv' ? '剧集' : '电影' }}
        </template>
      </el-table-column>
      <el-table-column prop="tmId" label="TMDB ID" width="100">
        <template #default="scope">
          <a v-if="scope.row.tmId" :href="'https://www.themoviedb.org/' + scope.row.type + '/' + scope.row.tmId"
             target="_blank">
            {{ scope.row.tmId }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="path" label="路径">
        <template #default="scope">
          <a :href="getUrl(scope.row)" target="_blank">
            {{ scope.row.path }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="year" label="年份" width="65"/>
      <el-table-column prop="score" label="评分" width="60"/>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button type="primary" size="small" @click="editMeta(scope.row)">编辑</el-button>
          <el-button type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div>
      <el-pagination layout="total, prev, pager, next, jumper, sizes" :current-page="page" @current-change="load"
                     :page-size="size" :page-sizes="sizes" :total="total" @size-change="handleSizeChange"/>
    </div>

    <el-dialog v-model="formVisible" :title="'编辑 '+form.id" width="60%">
      <el-form label-width="140px">
        <el-form-item label="站点" required>
          <el-select v-model="form.siteId">
            <el-option :label="site.name" :value="site.id" v-for="site of sites"/>
          </el-select>
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.type" class="ml-4">
            <el-radio label="movie" size="large">电影</el-radio>
            <el-radio label="tv" size="large">剧集</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="路径" required>
          <el-input v-model="form.path" autocomplete="off" readonly/>
        </el-form-item>
        <el-form-item label="TMDB ID" required>
          <el-input-number v-model="form.tmId" min="0" autocomplete="off"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!form.tmId" @click="updateMeta">更新</el-button>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="搜索">
          <a :href="'https://www.themoviedb.org/search/'+form.type+'?query='+form.name"
             target="_blank">{{ form.name }}</a>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!form.name" @click="scrape">刮削</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button type="danger" @click="dialogVisible=true">删除</el-button>
        <el-button @click="formVisible=false">取消</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="addVisible" title="添加电影数据" width="60%">
      <el-form label-width="140px">
        <el-form-item label="站点" required>
          <el-select v-model="form.siteId">
            <el-option :label="site.name" :value="site.id" v-for="site of sites"/>
          </el-select>
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.type" class="ml-4">
            <el-radio label="movie" size="large">电影</el-radio>
            <el-radio label="tv" size="large">剧集</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="路径" required>
          <el-input v-model="form.path" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="TMDB ID" required>
          <el-input-number v-model="form.tmId" min="0" autocomplete="off"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="addVisible=false">取消</el-button>
        <el-button type="primary" :disabled="!form.path||!form.tmId" @click="saveMeta">保存</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除电影数据" width="30%">
      <div v-if="batch">
        <p>是否删除选中的{{ multipleSelection.length }}个电影数据?</p>
      </div>
      <div v-else>
        <p>是否删除电影数据 - {{ form.name }} {{ form.year }}</p>
        <p>{{ form.path }}</p>
      </div>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSub">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="scrapeVisible" title="刮削索引文件">
      <el-form-item label="站点">
        <el-select v-model="siteId" @change="loadIndexFiles">
          <el-option :label="site.name" :value="site.id" v-for="site of sites"/>
        </el-select>
      </el-form-item>
      <el-form-item label="强制更新？">
        <el-switch v-model="force"/>
      </el-form-item>
      <el-form-item label="索引名称">
        <el-select v-model="indexName">
          <el-option v-for="item in index" :key="item.name" :label="item.name" :value="item.name"/>
        </el-select>
      </el-form-item>
      <p v-if="indexName">索引文件：/data/index/{{ siteId }}/{{ indexName }}.txt</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="scrapeVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!indexName" @click="scrapeIndex">刮削</el-button>
      </span>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"
import {ElMessage} from "element-plus";
import {store} from "@/services/store";
import type {Site} from "@/model/Site";
import type {Meta} from "@/model/Meta";

const sizes = [20, 40, 60, 80, 100]
const url = ref('http://' + window.location.hostname + ':' + (store.hostmode ? 5678 : 5344))
const keyword = ref('')
const indexName = ref('custom_index')
const force = ref(false)
const siteId = ref(1)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const files = ref([])
const sites = ref([] as Site[])
const index = ref<Meta[]>([])
const multipleSelection = ref<Meta[]>([])
const dialogVisible = ref(false)
const formVisible = ref(false)
const addVisible = ref(false)
const scrapeVisible = ref(false)
const batch = ref(false)
const form = ref({
  id: 0,
  name: '',
  path: '',
  type: 'movie',
  year: 0,
  score: 0,
  tmId: 0,
  siteId: 1,
})

const handleSelectionChange = (val: Meta[]) => {
  multipleSelection.value = val
}

const handleDelete = (data: Meta) => {
  form.value = data
  batch.value = false
  dialogVisible.value = true
}

const handleDeleteBatch = () => {
  batch.value = true
  dialogVisible.value = true
}

const deleteSub = () => {
  dialogVisible.value = false
  if (batch.value) {
    axios.post('/api/tmdb/meta-batch-delete', multipleSelection.value.map(s => s.id)).then(() => {
      dialogVisible.value = false
      scrapeVisible.value = false
      refresh()
    })
  } else {
    axios.delete('/api/tmdb/meta/' + form.value.id).then(() => {
      dialogVisible.value = false
      scrapeVisible.value = false
      refresh()
    })
  }
}

const search = () => {
  load(1)
}

const refresh = () => {
  load(page.value)
}

const fixMeta = () => {
  axios.post('/api/tmdb/fix-meta').then(({data}) => {
    ElMessage.success('删除' + data + '个重复数据')
  })
}

const getUrl = (meta: Meta) => {
  const site = sites.value.find(e => e.id == meta.siteId)
  if (site && site.url !== 'http://localhost') {
    let surl = site.url
    if (surl.endsWith('/')) {
      surl = surl.substring(0, surl.length - 1)
    }
    return surl + meta.path
  }
  return url.value + meta.path
}

const syncMeta = () => {
  axios.post('/api/tmdb/meta-sync').then(() => {
    ElMessage.success('开始同步电影数据')
  })
}

const addMeta = () => {
  form.value = {
    id: 0,
    name: '',
    path: '',
    type: 'movie',
    year: 0,
    score: 0,
    tmId: 0,
    siteId: 1,
  }
  addVisible.value = true
}

const saveMeta = () => {
  form.value.tmId = +form.value.tmId
  axios.post('/api/tmdb/meta', form.value).then(({data}) => {
    if (data) {
      ElMessage.success('保存成功')
      addVisible.value = false
      refresh()
    } else {
      ElMessage.warning('保存失败')
    }
  })
}

const editMeta = (data: any) => {
  form.value = Object.assign({siteId: 1}, data)
  formVisible.value = true
}

const updateMeta = () => {
  axios.post('/api/tmdb/meta/' + form.value.id, form.value).then(({data}) => {
    if (data) {
      ElMessage.success('更新成功')
      formVisible.value = false
      refresh()
    } else {
      ElMessage.warning('更新失败')
    }
  })
}

const scrape = () => {
  axios.post('/api/tmdb/meta/' + form.value.id + '/scrape?name=' + form.value.name + '&type=' + form.value.type).then(({data}) => {
    if (data) {
      ElMessage.success('刮削成功')
      formVisible.value = false
      refresh()
    } else {
      ElMessage.warning('刮削失败')
    }
  })
}

const showScrapeIndex = () => {
  scrapeVisible.value = true
  loadIndexFiles()
}

const scrapeIndex = () => {
  axios.post('/api/tmdb/meta-scrape?siteId=' + siteId.value + '&indexName=' + indexName.value + '&force=' + force.value).then(() => {
    ElMessage.success('刮削开始')
    scrapeVisible.value = false
  })
}

const handleSizeChange = (value: number) => {
  size.value = value
  load(1)
}

const load = (value: number) => {
  page.value = value
  axios.get('/api/tmdb/meta?page=' + (page.value - 1) + '&size=' + size.value + '&q=' + keyword.value).then(({data}) => {
    files.value = data.content
    total.value = data.totalElements
  })
}

const loadSites = () => {
  axios.get('/api/sites').then(({data}) => {
    sites.value = data
    if (sites.value && sites.value.length > 0) {
      siteId.value = sites.value[0].id
    }
  })
}

const loadIndexFiles = () => {
  indexName.value = ''
  axios.get('/api/sites/' + siteId.value + '/index').then(({data}) => {
    index.value = data
    if (index.value && index.value.length > 0) {
      indexName.value = index.value[0].name
    }
  })
}

const loadBaseUrl = () => {
  if (store.baseUrl) {
    url.value = store.baseUrl
    return
  }

  axios.get('/api/sites/1').then(({data}) => {
    url.value = data.url
    const re = /http:\/\/localhost:(\d+)/.exec(data.url)
    if (re) {
      url.value = 'http://' + window.location.hostname + ':' + re[1]
      store.baseUrl = url.value
      console.log('load AList ' + url.value)
    } else if (data.url == 'http://localhost') {
      axios.get('/api/alist/port').then(({data}) => {
        if (data) {
          url.value = 'http://' + window.location.hostname + ':' + data
          store.baseUrl = url.value
          console.log('load AList ' + url.value)
        }
      })
    } else {
      store.baseUrl = url.value
      console.log('load AList ' + url.value)
    }
  })
}

onMounted(() => {
  loadBaseUrl()
  loadSites()
  refresh()
})
</script>

<style scoped>
.search {
  width: 350px;
}

.space {
  margin-bottom: 6px;
}

.el-input-number {
  width: 200px;
}
</style>
