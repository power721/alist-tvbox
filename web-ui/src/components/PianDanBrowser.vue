<template>
  <el-dialog :model-value="modelValue" title="片单榜单(豆瓣/TMDB 热门榜单选剧)" width="960" top="3vh"
             @update:model-value="emit('update:modelValue', $event)">
    <div class="nav-toolbar">
      <el-select v-model="navType" filterable style="width: 240px" @change="onNavTypeChange">
        <el-option v-for="item in navCategories" :key="item.type_id" :label="item.type_name" :value="item.type_id"/>
      </el-select>
      <el-select v-for="f in navFilterDefs" :key="f.key" v-model="navFilters[f.key]" :placeholder="f.name"
                 clearable style="width: 132px" @change="onNavFilterChange">
        <el-option v-for="option in f.value" :key="option.v" :label="option.n" :value="option.v"/>
      </el-select>
      <span class="sub-text">共 {{ navTotal }} 条 · 「想看」入稍后再看队列,「追更」交给宿主页订阅</span>
    </div>
    <div class="nav-grid" v-loading="navLoading">
      <div v-for="item in navList" :key="item.vod_id" class="nav-card">
        <el-image :src="item.vod_pic" fit="cover" class="nav-cover cover-click" lazy @click="showDetail(item)">
          <template #error>
            <div class="nav-cover nav-cover-placeholder cover-click" @click="showDetail(item)">{{ (item.vod_name || '?').charAt(0) }}</div>
          </template>
        </el-image>
        <div class="nav-title" :title="item.vod_name" @click="showDetail(item)">{{ item.vod_name }}</div>
        <div class="nav-meta">
          <span v-if="item.vod_remarks">{{ item.vod_remarks }}</span>
          <span v-if="item.vod_year">{{ item.vod_year }}</span>
          <span v-if="item.type_name">{{ item.type_name }}</span>
        </div>
        <div class="nav-actions">
          <!-- 已追更的剧不再进想看队列(想看=还没追先标记,已追有订阅在追更,入队冗余):直接只留「已追更」 -->
          <el-button v-if="!isSubscribed(item)" size="small" :type="isWanted(item) ? 'info' : 'warning'"
                     :disabled="isWanted(item)"
                     @click="want(item)">{{ isWanted(item) ? '已想看' : '想看' }}</el-button>
          <el-button v-if="isSubscribed(item)" size="small" disabled>已追更</el-button>
          <el-button v-else size="small" type="primary" @click="emit('subscribe', item)">追更</el-button>
        </div>
      </div>
    </div>
    <div class="nav-pager" v-if="navPageCount > 1">
      <el-pagination background layout="prev, pager, next" :total="navTotal" :page-size="24"
                     :current-page="navPage" @current-change="onNavPageChange"/>
    </div>
  </el-dialog>

  <PianDanDetailDialog v-model="detailVisible" :item="detailItem">
    <template #actions>
      <el-button v-if="!detailItem || !isSubscribed(detailItem)"
                 :type="detailItem && isWanted(detailItem) ? 'info' : 'warning'"
                 :disabled="!detailItem || isWanted(detailItem)" @click="detailItem && want(detailItem)">
        {{ detailItem && isWanted(detailItem) ? '已想看' : '想看' }}
      </el-button>
      <el-button v-if="detailItem && isSubscribed(detailItem)" disabled>已追更</el-button>
      <el-button v-else type="primary" @click="detailSubscribe">追更</el-button>
    </template>
  </PianDanDetailDialog>
</template>

<script setup lang="ts">
/**
 * 片单榜单浏览器(豆瓣/TMDB 分类+筛选+海报网格+详情),从追剧页「片单追更」对话框抽出通用化:
 * 「想看」内置(POST /api/watchlist,已想看回显按 vodId+标题双匹配——豆瓣榜单条目无 subject id 只有标题);
 * 「追更」emit 给宿主页(追剧页预填订阅对话框 / 想看页直接建订阅)。
 */
import {ref, watch} from 'vue'
import axios from 'axios'
import {ElMessage} from 'element-plus'
import PianDanDetailDialog from '@/components/PianDanDetailDialog.vue'

const props = defineProps({
  modelValue: {type: Boolean, default: false},
  /** 已追标题集(回显「已追更」禁用追更按钮),由宿主页按订阅数据传 */
  subscribedNames: {type: Array as () => string[], default: () => []}
})
const emit = defineEmits<{ 'update:modelValue': [value: boolean], subscribe: [item: any], wanted: [item: any] }>()

const navCategories = ref<{ type_id: string, type_name: string }[]>([])
const navAllFilters = ref<Record<string, any[]>>({})
const navFilterDefs = ref<any[]>([])
const navFilters = ref<Record<string, string>>({})
const navType = ref('douban:hot_tv')
const navList = ref<any[]>([])
const navPage = ref(1)
const navPageCount = ref(1)
const navTotal = ref(0)
const navLoading = ref(false)
let navSeq = 0

const detailVisible = ref(false)
const detailItem = ref<any>(null)

/** 已想看回显:打开时拉一次队列,vodId(tmdb 条目)+标题(豆瓣条目无 id 形态)双匹配 */
const wantedVodIds = ref(new Set<string>())
const wantedTitles = ref(new Set<string>())

watch(() => props.modelValue, (visible) => {
  if (!visible) return
  if (!navCategories.value.length) {
    axios.get('/api/media-subscriptions/navigation').then(response => {
      // CategoryList 的分类字段经 @JsonProperty 序列化为 "class"
      navCategories.value = ((response.data['class'] || []) as any[]).filter((c: any) => c.type_id && c.type_id !== '0')
      navAllFilters.value = response.data.filters || {}
      if (!navCategories.value.some(c => c.type_id === navType.value)) {
        navType.value = navCategories.value[0]?.type_id || ''
      }
      applyNavFilters()
      loadNavList()
    }).catch(() => ElMessage.error('片单分类加载失败'))
  }
  axios.get('/api/watchlist').then(response => {
    const items: any[] = response.data || []
    wantedVodIds.value = new Set(items.map(i => String(i.vodId || '')))
    wantedTitles.value = new Set(items.map(i => i.title))
  }).catch(() => {
  })
})

/** 分类切换:换用该分类的筛选定义(地区/年代/排序等,TVBox filter 同源),已选筛选清空。 */
const applyNavFilters = () => {
  navFilterDefs.value = navAllFilters.value[navType.value] || []
  navFilters.value = {}
}

const onNavTypeChange = () => {
  navPage.value = 1
  applyNavFilters()
  loadNavList()
}

const onNavFilterChange = () => {
  navPage.value = 1
  loadNavList()
}

const onNavPageChange = (page: number) => {
  navPage.value = page
  loadNavList()
}

const loadNavList = () => {
  if (!navType.value) return
  navLoading.value = true
  const params: any = {t: navType.value, pg: navPage.value, size: 24}
  Object.entries(navFilters.value).forEach(([key, value]) => {
    if (value) {
      params[key] = value // 空串 = "全部"选项,不传参
    }
  })
  const my = ++navSeq
  axios.get('/api/media-subscriptions/navigation/list', {params}).then(response => {
    if (my !== navSeq) return
    const data = response.data || {}
    navList.value = data.list || []
    navPageCount.value = data.pagecount || 1
    navTotal.value = data.total || navList.value.length
  }).catch(() => ElMessage.error('片单加载失败,该分类可能依赖外部接口')).finally(() => {
    if (my === navSeq) navLoading.value = false
  })
}

const isSubscribed = (item: any) => props.subscribedNames.includes(item.vod_name)

const isWanted = (item: any) => wantedVodIds.value.has(String(item.vod_id || '')) || wantedTitles.value.has(item.vod_name)

/** 想看:tmdb 条目带真 vodId(详情/转订阅保真),豆瓣榜单条目回落标题形态 */
const want = (item: any) => {
  if (isWanted(item)) return
  const body: any = {title: item.vod_name}
  const vodId = String(item.vod_id || '')
  if (vodId.startsWith('tmdb:')) {
    body.vodId = vodId
  }
  if (item.vod_year) body.year = item.vod_year
  axios.post('/api/watchlist', body).then(({data}) => {
    ElMessage.success(data.msg || '已加入稍后再看')
    wantedVodIds.value = new Set([...wantedVodIds.value, vodId])
    wantedTitles.value = new Set([...wantedTitles.value, item.vod_name])
    emit('wanted', item) // 宿主页刷新想看列表(队列可能正展示在别的 tab)
  })
}

const showDetail = (item: any) => {
  detailItem.value = item
  detailVisible.value = true
}

const detailSubscribe = () => {
  detailVisible.value = false
  if (detailItem.value) emit('subscribe', detailItem.value)
}
</script>

<style scoped>
.sub-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cover-click {
  cursor: pointer;
}

.nav-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
  margin-bottom: 12px;
}

.nav-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 12px;
  min-height: 200px;
}

.nav-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 4px;
}

.nav-cover {
  width: 100%;
  aspect-ratio: 2 / 3;
  border-radius: 4px;
  background: var(--el-fill-color);
}

.nav-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-dark);
}

.nav-title {
  font-size: 13px;
  line-height: 1.3;
  height: 34px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  cursor: pointer;
}

.nav-meta {
  display: flex;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  min-height: 18px;
}

.nav-actions {
  display: flex;
  gap: 4px;
}

.nav-pager {
  display: flex;
  justify-content: center;
  margin-top: 14px;
}
</style>
