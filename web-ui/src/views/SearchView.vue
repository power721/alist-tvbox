<template>
  <div class="search">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl+getPath(type)+'/'+store.token+'?wd=' + keyword"
         target="_blank">{{ currentUrl }}{{ getPath(type) }}/{{ store.token }}?wd={{ keyword }}</a>
    </div>

    <div>
      <el-input v-model="keyword" @change="search"/>
      <el-button type="primary" @click="search" :disabled="!keyword || searching">搜索</el-button>
      <el-button type="primary" @click="showDialog" v-if="store.admin">设置</el-button>
    </div>

    <el-form-item label="类型" label-width="140">
      <el-radio-group v-model="type" @change="search" class="ml-4">
        <el-radio label="1" size="large">点播模式</el-radio>
        <el-radio label="" size="large">网盘模式</el-radio>
        <el-radio label="2" size="large">BiliBili</el-radio>
        <el-radio label="4" size="large">Emby</el-radio>
        <el-radio label="5" size="large">Jellyfin</el-radio>
        <el-radio label="7" size="large">飞牛影视</el-radio>
        <el-radio label="6" size="large">鱼佬盘搜</el-radio>
      </el-radio-group>
    </el-form-item>

    <a href="/#/meta" v-if="store.admin">豆瓣电影数据列表</a>
    <span class="divider" v-if="store.admin"></span>
    <a href="/#/tmdb" v-if="store.admin">TMDB电影数据列表</a>

    <div class="actions" v-if="type=='6'&&config?.list?.length">
      <span>{{ filteredPanSouResults.length }}/{{ config.list.length }}条搜索结果</span>
      <el-select style="width: 100px;margin: 0 12px;" v-model="panSouType">
        <el-option
          v-for="item in panSouTypeOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-button type="primary" :loading="checkingLinks" :disabled="searching || !filteredPanSouResults.length" @click="checkLinks">
        检测有效性
      </el-button>
      <el-button type="success" :loading="checkingLinks" :disabled="searching || !filteredPanSouResults.length" @click="checkLinksConcurrently">
        并发检测
      </el-button>
    </div>

    <el-table v-if="(type==''||type=='1')&&config" :data="config.list" border style="width: 100%">
      <el-table-column prop="vod_name" label="名称" width="300">
        <template #default="scope">
          <a :href="'/#/vod'+scope.row.vod_content" target="_blank">
            {{ scope.row.vod_name }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_content" label="路径">
        <template #default="scope">
          <a :href="'/#/vod'+scope.row.vod_content" target="_blank">
            {{ scope.row.vod_content }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_year" label="年份" width="90"/>
      <el-table-column prop="vod_remarks" label="评分" width="100"/>
    </el-table>

    <el-table v-if="(type=='6')&&config" :data="filteredPanSouResults" border style="width: 100%" v-loading="searching">
      <el-table-column prop="vod_name" label="名称" sortable>
        <template #default="scope">
          <a :href="'/#/vod?link='+scope.row.vod_id" target="_blank">
            {{ scope.row.vod_name }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_id" label="链接" width="350" sortable>
        <template #default="scope">
          <a :href="decodeURIComponent(scope.row.vod_id)" target="_blank">
            {{ formatDisplayLink(scope.row.vod_id) }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_remarks" label="类型" width="100" sortable/>
      <el-table-column prop="vod_play_from" label="来源" width="180" sortable/>
      <el-table-column prop="vod_time" label="时间" width="180" sortable/>
      <el-table-column prop="validity_summary" label="有效性" width="180" sortable>
        <template #default="scope">
          <el-tag v-if="scope.row.validity_state" :type="getValidityTagType(scope.row.validity_state)">
            {{ scope.row.validity_summary || scope.row.validity_state }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="scope">
          <el-button link type="primary" :disabled="!isCheckSupportedRow(scope.row)" :loading="scope.row.validity_checking" @click="checkLink(scope.row)">
            检测
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <h2 v-if="type!='6'">API返回数据</h2>
    <div class="data" v-if="type!='6'">
      <json-viewer :value="config" expanded copyable show-double-quotes :show-array-index="false" :expand-depth=3>
      </json-viewer>
    </div>

    <el-dialog v-model="dialogVisible" title="配置搜索源">
      <el-form label-width="auto">
        <el-form-item label="搜索文件">
          <el-checkbox-group v-model="form.searchSources">
            <el-checkbox :value="file" name="index" v-for="file in form.files">
              {{ file }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="排除路径">
          <el-input v-model="form.excludedPaths" type="textarea" :rows="15" :placeholder="'多行以/开头的路径'"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="update">更新</el-button>
      </span>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'
import axios from "axios"
import {ElMessage} from "element-plus";
import {store} from "@/services/store";

const type = ref(localStorage.getItem("search_type") || '1');
const keyword = ref(localStorage.getItem("search_keyword") || '')
const panSouType = ref('ALL')
const config = ref<any>('')
const searching = ref(false)
const checkingLinks = ref(false)
const dialogVisible = ref(false)
const currentUrl = window.location.origin
const form = ref({
  files: [],
  searchSources: [],
  excludedPaths: '',
})

const getPath = (type: string) => {
  if (type == '1') {
    return '/vod1'
  } else if (type == '2') {
    return '/bilibili'
  } else if (type == '3') {
    return '/youtube'
  } else if (type == '4') {
    return '/emby'
  } else if (type == '5') {
    return '/jellyfin'
  } else if (type == '7') {
    return '/feiniu'
  } else if (type == '6') {
    return '/pansou'
  } else {
    return '/vod'
  }
}

const diskTypeMap: Record<string, string> = {
  '百度': 'baidu',
  '阿里': 'aliyun',
  '夸克': 'quark',
  '天翼': 'tianyi',
  'UC': 'uc',
  '移动': 'mobile',
  '115': '115',
  '迅雷': 'xunlei',
  '123': '123',
  '光鸭': 'guangya',
  '光鸭云盘': 'guangya',
}

const panSouItems = computed(() => config.value?.list || [])

const panSouTypeOptions = computed(() => {
  const types = Array.from(new Set(panSouItems.value.map((e: any) => e.vod_remarks).filter((e: string) => e)))
  return [
    {label: '全部', value: 'ALL'},
    ...types.map((type) => ({label: type, value: type})),
  ]
})

const filteredPanSouResults = computed(() => {
  if (panSouType.value == 'ALL') {
    return panSouItems.value
  }
  return panSouItems.value.filter((e: any) => e.vod_remarks == panSouType.value)
})

const getValidityTagType = (state: string) => {
  if (state === 'ok') {
    return 'success'
  }
  if (state === 'bad') {
    return 'danger'
  }
  if (state === 'locked') {
    return 'warning'
  }
  return 'info'
}

const isCheckSupportedRow = (row: any) => {
  return !!diskTypeMap[row.vod_remarks]
}

const formatDisplayLink = (vodId: string) => {
  const link = decodeURIComponent(vodId)
  if (!link.startsWith('magnet:')) {
    return link
  }
  try {
    const url = new URL(link)
    url.searchParams.delete('dn')
    return url.toString()
  } catch (e) {
    return decodeURIComponent(vodId)
  }
}

const search = function () {
  if (searching.value) {
    return
  }
  localStorage.setItem('search_type', type.value)
  localStorage.setItem('search_keyword', keyword.value.trim())
  if (!keyword.value) {
    return
  }
  searching.value = true
  config.value = ''
  axios.get(getPath(type.value) + '/' + store.token + '?ac=web&wd=' + keyword.value.trim()).then(({data}) => {
    config.value = data
    if (type.value == '6' && panSouType.value != 'ALL' && !panSouItems.value.some((e: any) => e.vod_remarks == panSouType.value)) {
      panSouType.value = 'ALL'
    }
  }).finally(() => {
    searching.value = false
  })
}

const checkLinks = () => {
  if (checkingLinks.value || !config.value?.list?.length) {
    return
  }
  const items: any[] = []
  filteredPanSouResults.value.forEach((row: any) => {
    const diskType = diskTypeMap[row.vod_remarks]
    if (!diskType) {
      return
    }
    items.push({
      disk_type: diskType,
      url: decodeURIComponent(row.vod_id),
    })
  })
  if (!items.length) {
    ElMessage.info('没有可检测的链接')
    return
  }
  checkingLinks.value = true
  checkPanSouItems(items).then((results) => {
    config.value.list.forEach((row: any) => updateLinkValidity(row, results))
  }).finally(() => {
    checkingLinks.value = false
  })
}

const checkLinksConcurrently = () => {
  if (checkingLinks.value || !filteredPanSouResults.value.length) {
    return
  }
  const pending = collectCheckItems()
  if (!pending.length) {
    ElMessage.info('没有可检测的链接')
    return
  }
  const chunkSize = 5
  const chunks = []
  for (let i = 0; i < pending.length; i += chunkSize) {
    chunks.push(pending.slice(i, i + chunkSize))
  }
  checkingLinks.value = true
  pending.forEach(e => {
    e.row.validity_checking = true
  })
  Promise.all(chunks.map(chunk => {
    return checkPanSouItems(chunk.map(e => e.item)).then((results) => {
      chunk.forEach(e => updateLinkValidity(e.row, results))
    }).finally(() => {
      chunk.forEach(e => {
        e.row.validity_checking = false
      })
    })
  })).finally(() => {
    checkingLinks.value = false
  })
}

const checkLink = (row: any) => {
  const diskType = diskTypeMap[row.vod_remarks]
  if (!diskType) {
    return
  }
  row.validity_checking = true
  checkPanSouItems([{
    disk_type: diskType,
    url: decodeURIComponent(row.vod_id),
  }]).then((results) => {
    updateLinkValidity(row, results)
  }).finally(() => {
    row.validity_checking = false
  })
}

const checkPanSouItems = (items: any[]) => {
  return axios.post('/api/pansou/check/links', {
    items,
    view_token: 'pansou-' + Date.now(),
  }).then(({data}) => new Map((data.results || []).map((e: any) => [e.url, e])))
}

const collectCheckItems = () => {
  const pending: any[] = []
  filteredPanSouResults.value.forEach((row: any) => {
    const diskType = diskTypeMap[row.vod_remarks]
    if (!diskType) {
      return
    }
    pending.push({
      row,
      item: {
        disk_type: diskType,
        url: decodeURIComponent(row.vod_id),
      },
    })
  })
  return pending
}

const updateLinkValidity = (row: any, results: Map<any, any>) => {
  const result: any = results.get(decodeURIComponent(row.vod_id))
  if (result) {
    row.validity_state = result.state
    row.validity_summary = result.summary || result.state
  }
}

const showDialog = () => {
  axios.get('/api/index-files/settings').then(({data}) => {
    data.excludedPaths = data.excludedPaths.replace(/,/g, '\n')
    form.value = data
    dialogVisible.value = true
  })
}

const update = () => {
  const rule = Object.assign({}, form.value)
  rule.excludedPaths = rule.excludedPaths.replace(/\n/g, ',')
  axios.post('/api/index-files/settings', rule).then(() => {
    ElMessage.success('更新成功')
  })
}
</script>

<style scoped>
.description {
  margin-bottom: 12px;
}

.actions {
  margin: 12px 0;
}

.divider {
  margin-left: 24px;
}
</style>
