<template>
  <div :class="embedded ? '' : 'page-container'">
    <div class="page-header" v-if="!embedded">
      <h1 class="page-title">稍后再看</h1>
      <div class="page-actions">
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="addVisible = true">添加</el-button>
        <el-button @click="browserVisible = true">逛榜单</el-button>
      </div>
    </div>

    <div :class="embedded ? '' : 'page-card'">
      <div class="batch-bar" v-if="embedded">
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" type="primary" @click="addVisible = true">添加</el-button>
        <el-button size="small" @click="browserVisible = true">逛榜单</el-button>
        <span class="sub-text" style="margin-left: 10px">片单/榜单标记的想看队列,追剧前的中转站</span>
      </div>
      <div class="batch-bar" v-if="items.length">
        <el-select v-model="statusFilter" size="small" style="width: 110px" placeholder="全部状态">
          <el-option label="全部状态" value=""/>
          <el-option label="想看" value="WANT"/>
          <el-option label="已看" value="WATCHED"/>
          <el-option label="收藏" value="FAVORITE"/>
        </el-select>
        <el-divider direction="vertical"/>
        <el-button size="small" @click="setSelection(true)">全选</el-button>
        <el-button size="small" @click="setSelection(false)">全不选</el-button>
        <el-button size="small" @click="invertSelection">反选</el-button>
        <el-divider direction="vertical"/>
        <el-button size="small" type="danger" :disabled="!selected.length" @click="batchRemove">批量移出</el-button>
        <span class="sub-text" style="margin-left: 10px">已选 {{ selected.length }} 项</span>
      </div>
      <div class="table-scroll-wrapper">
        <el-table ref="tableRef" :data="filteredItems" border style="width: 100%; min-width: 900px" v-loading="loading"
                  @selection-change="(rows: any[]) => selected = rows">
          <el-table-column type="selection" width="45"/>
          <el-table-column label="封面" width="70">
            <template #default="scope">
              <el-image v-if="scope.row.pic" :src="scope.row.pic" lazy fit="cover"
                        style="width: 45px; height: 60px; border-radius: 4px; cursor: pointer"
                        @click="openDetail(scope.row)"/>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="标题" min-width="200">
            <template #default="scope">
              <span class="title-link" @click="openDetail(scope.row)">
                {{ scope.row.season ? scope.row.title + ' 第' + scope.row.season + '季' : scope.row.title }}
              </span>
              <el-tag v-if="scope.row.subscribed" type="success" size="small" style="margin-left: 6px">已追</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="year" label="年份" width="80"/>
          <el-table-column prop="remarks" label="评分快照" width="110"/>
          <el-table-column label="来源" width="90">
            <template #default="scope">
              <el-tag size="small" :type="sourceType(scope.row.vodId).tag">{{ sourceType(scope.row.vodId).label }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="scope">
              <el-tag size="small" :type="statusTag(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="加入时间" width="110">
            <template #default="scope">{{ formatDate(scope.row.createdTime) }}</template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="200">
            <template #default="scope">
              <el-button link type="primary" size="small" :disabled="scope.row.subscribed"
                         @click="subscribe(scope.row)">{{ scope.row.subscribed ? '已追剧' : '追剧' }}</el-button>
              <el-button link size="small" @click="cycleStatus(scope.row)">{{ nextStatusText(scope.row.status) }}</el-button>
              <el-button link type="danger" size="small" @click="remove(scope.row)">移出</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && !items.length" description="暂无稍后再看条目 — TVBox/WebHome 片单详情页点「➕ 稍后再看」即可加入"/>
      </div>
    </div>
  </div>

  <el-dialog v-model="addVisible" title="添加稍后再看" width="480px">
    <el-form :model="addForm" label-width="90">
      <el-form-item label="标题" required>
        <el-input v-model="addForm.title" placeholder="剧名/片名" autocomplete="off"/>
      </el-form-item>
      <el-form-item label="年份">
        <el-input v-model="addForm.year" placeholder="可选,同名翻拍消歧" autocomplete="off"/>
      </el-form-item>
      <el-form-item label="季">
        <el-input v-model="addForm.season" placeholder="可选,数字" autocomplete="off"/>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="addVisible = false">取消</el-button>
      <el-button type="primary" @click="add">加入</el-button>
    </template>
  </el-dialog>

  <PianDanBrowser v-model="browserVisible" :subscribed-names="subscribedNames"
                  @subscribe="subscribeFromBoard" @wanted="load"/>

  <PianDanDetailDialog v-model="detailVisible" :item="detailItem">
    <template #actions>
      <el-button type="danger" v-if="detailRow" @click="removeFromDetail">移出稍后再看</el-button>
      <el-button type="primary" v-if="detailRow && detailRow.subscribed" disabled>已追剧</el-button>
      <el-button type="primary" v-else-if="detailRow" @click="subscribeFromDetail">追剧</el-button>
    </template>
  </PianDanDetailDialog>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import axios from 'axios'
import {ElMessage} from 'element-plus'
import PianDanBrowser from '@/components/PianDanBrowser.vue'
import PianDanDetailDialog from '@/components/PianDanDetailDialog.vue'

defineProps({embedded: {type: Boolean, default: false}})
const emit = defineEmits<{ subscribed: [] }>()
defineExpose({load: () => load()})

const loading = ref(false)
const items = ref<any[]>([])
const selected = ref<any[]>([])
const statusFilter = ref('')
const tableRef = ref()
const addVisible = ref(false)
const addForm = ref({title: '', year: '', season: ''})
/** 逛榜单(豆瓣/TMDB 榜单浏览,组件内置「想看」;「追更」回到本页直接建订阅) */
const browserVisible = ref(false)
const subscribedNames = computed(() =>
    items.value.filter(i => i.subscribed).map(i => i.season ? i.title + ' 第' + i.season + '季' : i.title))
/** 条目媒体详情(点封面/标题打开):vodId 为片单形态,navigation/detail 直接可用 */
const detailVisible = ref(false)
const detailRow = ref<any>(null)
const detailItem = computed(() => detailRow.value ? {
  vod_id: detailRow.value.vodId,
  vod_name: detailRow.value.season ? detailRow.value.title + ' 第' + detailRow.value.season + '季' : detailRow.value.title,
  vod_pic: detailRow.value.pic,
  vod_year: detailRow.value.year,
  vod_remarks: detailRow.value.remarks
} : null)

function openDetail(row: any) {
  detailRow.value = row
  detailVisible.value = true
}

function removeFromDetail() {
  if (!detailRow.value) return
  const id = detailRow.value.id
  detailVisible.value = false
  axios.delete('/api/watchlist/' + id).then(load)
}

function subscribeFromDetail() {
  if (!detailRow.value) return
  const row = detailRow.value
  detailVisible.value = false
  subscribe(row)
}

const filteredItems = computed(() =>
    statusFilter.value ? items.value.filter(i => i.status === statusFilter.value) : items.value)

onMounted(load)

function load() {
  loading.value = true
  axios.get('/api/watchlist').then(({data}) => {
    items.value = data
  }).finally(() => {
    loading.value = false
  })
}

function setSelection(checked: boolean) {
  filteredItems.value.forEach(row => tableRef.value?.toggleRowSelection(row, checked))
}

function invertSelection() {
  const checked = new Set(selected.value)
  filteredItems.value.forEach(row => tableRef.value?.toggleRowSelection(row, !checked.has(row)))
}

function batchRemove() {
  axios.post('/api/watchlist/batch-delete', {ids: selected.value.map(i => i.id)}).then(() => {
    ElMessage.success('已移出 ' + selected.value.length + ' 项')
    load()
  })
}

function remove(row: any) {
  axios.delete('/api/watchlist/' + row.id).then(load)
}

function add() {
  if (!addForm.value.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  axios.post('/api/watchlist', {
    title: addForm.value.title.trim(),
    year: addForm.value.year || null,
    season: addForm.value.season || null
  }).then(({data}) => {
    ElMessage.success(data.msg || '已加入')
    addVisible.value = false
    addForm.value = {title: '', year: '', season: ''}
    load()
  })
}

/** 追剧:tmdb:/db: 条目带元数据绑定(与片单详情「➕ 加入追剧」同效果),s: 回落纯标题。
 *  建订阅成功后刷新本列表(已追角标)+ emit 宿主页刷订阅列表(想看并入追剧页后两个 tab 都要新)。 */
function subscribe(row: any) {
  const body: any = {name: row.title, keyword: row.title}
  if (row.season) body.season = row.season
  const vodId = String(row.vodId || '')
  if (vodId.startsWith('tmdb:')) {
    const parts = vodId.split(':')
    if (parts.length >= 3) {
      body.metaProvider = 'tmdb'
      body.metaId = parts[2]
    }
  } else if (vodId.startsWith('db:')) {
    body.doubanId = Number(vodId.substring(3))
    body.metaProvider = 'douban'
    body.metaId = body.doubanId + ''
  }
  axios.post('/api/media-subscriptions', body).then(() => {
    ElMessage.success('已加入追剧,稍后到「追剧」页查看')
    load()
    emit('subscribed')
  })
}

/** 榜单「追更」:榜单条目字段名映射后走同一订阅路径。 */
function subscribeFromBoard(item: any) {
  subscribe({title: item.vod_name, vodId: String(item.vod_id || ''), season: null})
}

/** 状态循环:想看→已看→收藏→想看(二期完整流转,数据结构已就位)。 */
function cycleStatus(row: any) {
  const next = row.status === 'WANT' ? 'WATCHED' : row.status === 'WATCHED' ? 'FAVORITE' : 'WANT'
  axios.patch('/api/watchlist/' + row.id + '/status', {status: next}).then(load)
}

function statusText(status: string) {
  return status === 'WATCHED' ? '已看' : status === 'FAVORITE' ? '收藏' : '想看'
}

function statusTag(status: string) {
  return status === 'WATCHED' ? 'info' : status === 'FAVORITE' ? 'warning' : 'success'
}

function nextStatusText(status: string) {
  return status === 'WANT' ? '标已看' : status === 'WATCHED' ? '标收藏' : '回想看'
}

function sourceType(vodId: string) {
  const id = String(vodId || '')
  if (id.startsWith('tmdb:')) return {label: 'TMDB', tag: 'primary'}
  if (id.startsWith('db:')) return {label: '豆瓣', tag: ''}
  return {label: '标题', tag: 'info'}
}

function formatDate(ms: number) {
  if (!ms) return ''
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
</script>

<style scoped>
.title-link {
  cursor: pointer;
  color: var(--el-color-primary);
}

.title-link:hover {
  opacity: 0.8;
}
</style>
