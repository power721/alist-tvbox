<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">追剧订阅</h1>
      <div class="page-actions">
        <el-button @click="loadAll">刷新</el-button>
        <el-button @click="openNotify">通知</el-button>
        <el-button @click="exportSubs">导出</el-button>
        <el-button @click="importVisible = true">导入</el-button>
        <el-button type="primary" @click="handleAdd">新建订阅</el-button>
      </div>
    </div>

    <div class="stats-row" v-if="stats">
      <div class="stat-card"><div class="stat-value">{{ stats.total }}</div><div class="stat-label">订阅</div></div>
      <div class="stat-card"><div class="stat-value">{{ stats.active }}</div><div class="stat-label">追更中</div></div>
      <div class="stat-card"><div class="stat-value">{{ stats.todayNewEpisodes }}</div><div class="stat-label">今日更新</div></div>
      <div class="stat-card"><div class="stat-value">{{ stats.ended }}</div><div class="stat-label">已完结</div></div>
      <div class="stat-card danger"><div class="stat-value">{{ stats.error }}</div><div class="stat-label">异常</div></div>
    </div>

    <div class="page-card inbox-card" v-if="inboxItems.length">
      <div class="inbox-header" @click="inboxExpanded = !inboxExpanded">
        <b>今日/近日更新({{ inboxItems.length }})</b>
        <el-button link type="primary">{{ inboxExpanded ? '收起' : '展开' }}</el-button>
      </div>
      <el-timeline v-if="inboxExpanded" class="inbox-timeline">
        <el-timeline-item v-for="item in inboxItems.slice(0, 20)" :key="item.name + item.createdTime"
                          :timestamp="formatTime(item.createdTime)" :type="eventType(item.type)">
          {{ item.name }} · {{ eventTypeName(item.type) }} · {{ item.detail }}
        </el-timeline-item>
      </el-timeline>
    </div>

    <div class="page-card">
      <div class="batch-bar" v-if="subscriptions.length">
        <el-button size="small" @click="selectAll">全选</el-button>
        <el-button size="small" @click="selectNone">全不选</el-button>
        <el-button size="small" @click="invertSelection">反选</el-button>
        <el-divider direction="vertical"/>
        <el-button size="small" type="primary" :disabled="!selected.length" @click="batch('check')">批量检查</el-button>
        <el-button size="small" :disabled="!selected.length" @click="batch('pause')">批量暂停</el-button>
        <el-button size="small" :disabled="!selected.length" @click="batch('resume')">批量恢复</el-button>
        <el-button size="small" type="danger" :disabled="!selected.length" @click="batch('delete')">批量删除</el-button>
        <span class="sub-text" style="margin-left: 10px">已选 {{ selected.length }} 项</span>
      </div>
      <div class="table-scroll-wrapper">
        <el-table ref="tableRef" :data="subscriptions" border style="width: 100%; min-width: 1100px" v-loading="loading"
                  @selection-change="(rows: any[]) => selected = rows">
          <el-table-column type="selection" width="42"/>
          <el-table-column label="剧名" min-width="230">
            <template #default="scope">
              <div class="name-cell">
                <el-image :src="scope.row.cover" fit="cover" class="cover">
                  <template #error>
                    <div class="cover cover-placeholder">{{ scope.row.name.charAt(0) }}</div>
                  </template>
                </el-image>
                <div>
                  <div>{{ scope.row.name }}</div>
                  <div class="sub-text">
                    {{ scope.row.activeResourceTitle || scope.row.keyword }}
                    <el-tag v-if="scope.row.gapCount" size="small" type="warning" style="margin-left:4px">补缺×{{ scope.row.gapCount }}</el-tag>
                  </div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="scope">
              <el-tag :type="statusType(scope.row.status)" size="small">{{ statusText(scope.row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="进度" width="170">
            <template #default="scope">
              <span>{{ scope.row.currentEpisodes ?? 0 }}{{ scope.row.officialTotal ? ' / ' + scope.row.officialTotal : (scope.row.expectedEpisodes ? ' / ' + scope.row.expectedEpisodes : '') }} 集</span>
              <div class="sub-text danger" v-if="scope.row.missingEpisodes && scope.row.missingEpisodes.length">
                缺第 {{ compactNumbers(scope.row.missingEpisodes) }} 集
              </div>
              <div class="sub-text" v-else-if="scope.row.officialEpisodes && scope.row.officialEpisodes > (scope.row.currentEpisodes ?? 0)">
                官方已播 {{ scope.row.officialEpisodes }} 集
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="mode" label="模式" width="80">
            <template #default="scope">
              <el-tag size="small" :type="scope.row.mode === 'TRANSFER' ? 'success' : 'info'">
                {{ scope.row.mode === 'TRANSFER' ? '转存' : '挂载' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resourceCount" label="候选" width="65"/>
          <el-table-column label="检查/播出" width="210">
            <template #default="scope">
              <div class="sub-text" v-if="scope.row.nextAirTime">下集播出:{{ formatTime(scope.row.nextAirTime) }}</div>
              <div class="sub-text">下次检查:{{ formatTime(scope.row.nextCheckTime) }}</div>
              <div class="sub-text">上次检查:{{ formatTime(scope.row.lastCheckTime) }}</div>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="330">
            <template #default="scope">
              <el-button link type="primary" size="small" @click="checkNow(scope.row)">检查</el-button>
              <el-button link type="primary" size="small" @click="showResources(scope.row)">候选源</el-button>
              <el-button link type="primary" size="small" @click="showEpisodes(scope.row)">集数</el-button>
              <el-button link type="primary" size="small" @click="showEvents(scope.row)">动态</el-button>
              <el-button link type="primary" size="small" @click="togglePause(scope.row)">
                {{ scope.row.status === 'PAUSED' ? '恢复' : '暂停' }}
              </el-button>
              <el-button v-if="scope.row.mode === 'TRANSFER'" link type="success" size="small" @click="transferNow(scope.row)">转存</el-button>
              <el-button v-if="scope.row.status === 'ENDED'" link type="warning" size="small" @click="subscribeNextSeason(scope.row)">下一季</el-button>
              <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑订阅' : '新建订阅'" width="700" top="3vh">
      <el-form :model="form" label-width="120">
        <el-form-item label="剧名" required>
          <el-input v-model="form.name" placeholder="展示名称,如:边水往事"/>
        </el-form-item>
        <el-form-item label="搜索词">
          <el-input v-model="form.keyword" placeholder="默认同剧名;资源命名差异大时可填别名"/>
        </el-form-item>
        <el-form-item label="条目链接">
          <div class="meta-search">
            <el-input v-model="metaLink" placeholder="粘贴豆瓣/TMDB/Bangumi/腾讯视频条目链接,自动识别"
                      @keyup.enter="resolveLink"/>
            <el-button @click="resolveLink" :loading="resolvingLink">解析</el-button>
          </div>
          <span v-if="form.metaId" class="sub-text">已绑定:{{ providerName(form.metaProvider) }} {{ form.metaId }}</span>
        </el-form-item>
        <el-form-item label="元数据条目">
          <el-tabs v-model="metaProvider" style="width: 100%">
            <el-tab-pane label="豆瓣" name="douban"/>
            <el-tab-pane label="TMDB" name="tmdb"/>
            <el-tab-pane label="Bangumi" name="bangumi"/>
            <el-tab-pane label="官方平台" name="official"/>
            <el-tab-pane label="全部" name=""/>
          </el-tabs>
          <div class="meta-search">
            <el-input v-model="metaKeyword" placeholder="搜索条目(官方集数/完结判定/播出日程/封面)" @keyup.enter="searchMeta"/>
            <el-button @click="searchMeta" :loading="metaSearching">搜索</el-button>
          </div>
          <div v-if="metaResults.length" class="meta-results">
            <div v-for="item in metaResults" :key="item.provider + item.id" class="meta-item"
                 :class="{ selected: form.metaProvider === item.provider && form.metaId === item.id }"
                 @click="selectMeta(item)">
              <el-image :src="item.cover" fit="cover" class="meta-cover">
                <template #error><div class="meta-cover"></div></template>
              </el-image>
              <div class="meta-info">
                <div>{{ item.name }}({{ item.year }}){{ item.score ? ' ★' + item.score : '' }}</div>
                <div class="sub-text">{{ providerName(item.provider) }} · {{ item.id }}</div>
              </div>
            </div>
          </div>
          <div v-if="form.metaId" class="sub-text">已选:{{ providerName(form.metaProvider) }} {{ form.metaId }}</div>
        </el-form-item>
        <el-form-item label="季">
          <el-input-number v-model="form.season" :min="1" :max="50"/>
        </el-form-item>
        <el-form-item label="期望集数">
          <el-input-number v-model="form.expectedEpisodes" :min="0" :max="9999"/>
          <span class="sub-text" style="margin-left:8px">0/空 = 用官方总集数,均无则不自动完结</span>
        </el-form-item>
        <el-form-item label="资源模式">
          <el-radio-group v-model="form.mode">
            <el-radio value="FOLLOW">挂载追更(免账号)</el-radio>
            <el-radio value="TRANSFER">自动转存到我的网盘</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.mode === 'TRANSFER'" label="转存网盘">
          <el-select v-model="form.accountIds" multiple placeholder="可多选,同时转存到多个网盘" style="width: 100%">
            <el-option v-for="account in accounts" :key="account.id" :label="account.name + '(' + account.type + ')'" :value="account.id"/>
          </el-select>
          <span class="sub-text">转存到各网盘 /追剧/ 目录下;全部失败才降级挂载模式</span>
        </el-form-item>
        <el-form-item label="检查周期(时)">
          <el-input-number v-model="form.checkIntervalHours" :min="1" :max="168"/>
          <span class="sub-text" style="margin-left:8px">绑定元数据后按播出日程自动调度</span>
        </el-form-item>
        <el-form-item label="盘类型偏好">
          <el-select v-model="form.driveTypes" multiple placeholder="多选,按优先级排序">
            <el-option v-for="drive in driveOptions" :key="drive.value" :label="drive.label" :value="drive.value"/>
          </el-select>
        </el-form-item>
        <el-form-item label="清晰度">
          <el-select v-model="form.qualities" multiple allow-create placeholder="如 4K / 1080P">
            <el-option v-for="q in ['4K', '1080P', '720P']" :key="q" :label="q" :value="q"/>
          </el-select>
        </el-form-item>
        <el-form-item label="包含关键词">
          <el-select v-model="form.includeKeywords" multiple allow-create filterable placeholder="字幕组等,回车添加"/>
        </el-form-item>
        <el-form-item label="排除关键词">
          <el-select v-model="form.excludeKeywords" multiple allow-create filterable placeholder="如 预告/枪版,回车添加"/>
        </el-form-item>
        <el-form-item label="单集下限(MB)">
          <el-input-number v-model="form.minEpisodeSizeMb" :min="0" :max="100000"/>
          <span class="sub-text" style="margin-left:8px">过滤预告/花絮</span>
        </el-form-item>
        <el-form-item label="单集上限(MB)">
          <el-input-number v-model="form.maxEpisodeSizeMb" :min="0" :max="1000000"/>
          <span class="sub-text" style="margin-left:8px">0 = 不限;过滤捆绑包/异常大文件</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="preview" :loading="previewing">预览资源</el-button>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">{{ form.id ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" title="匹配预览(dry-run,未订阅)" width="800" top="3vh">
      <el-table :data="previewItems" border v-loading="previewing" max-height="480">
        <el-table-column prop="title" label="资源" min-width="260" show-overflow-tooltip/>
        <el-table-column prop="drive" label="盘" width="90"/>
        <el-table-column prop="score" label="评分" width="70" sortable/>
        <el-table-column prop="reasons" label="打分明细" min-width="200" show-overflow-tooltip/>
        <el-table-column prop="validity" label="有效性" width="90"/>
      </el-table>
    </el-dialog>

    <el-drawer v-model="resourcesVisible" :title="'候选资源 - ' + (current?.name || '')" size="62%">
      <el-table :data="resources" border v-loading="resourcesLoading">
        <el-table-column prop="title" label="资源" min-width="240" show-overflow-tooltip/>
        <el-table-column prop="driveName" label="盘" width="90"/>
        <el-table-column prop="score" label="评分" width="70" sortable/>
        <el-table-column label="有效性" width="90">
          <template #default="scope">
            <el-tag size="small" :type="validityType(scope.row.validity)">{{ scope.row.validity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="episodesFound" label="集数" width="70"/>
        <el-table-column label="角色" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.active" size="small" type="success">主源</el-tag>
            <el-tag v-else-if="scope.row.gap" size="small" type="warning">补缺</el-tag>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="90">
          <template #default="scope">
            <el-button v-if="!scope.row.active && scope.row.validity !== 'BAD'" link type="primary" size="small"
                       @click="activateResource(scope.row)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-drawer v-model="episodesVisible" :title="'集数清单 - ' + (current?.name || '')" size="42%">
      <el-table :data="episodeItems" border v-loading="episodesLoading" max-height="600">
        <el-table-column prop="episode" label="集" width="70" sortable/>
        <el-table-column label="状态" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.present" size="small" type="success">已有</el-tag>
            <el-tag v-else size="small" type="danger">缺失</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源"/>
      </el-table>
    </el-drawer>

    <el-drawer v-model="eventsVisible" :title="'更新动态 - ' + (current?.name || '')" size="45%">
      <el-timeline v-if="events.length">
        <el-timeline-item v-for="event in events" :key="event.id" :timestamp="formatTime(event.createdTime)"
                          :type="eventType(event.type)">
          {{ eventDetail(event) }}
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无动态"/>
    </el-drawer>

    <el-dialog v-model="importVisible" title="导入订阅" width="600">
      <el-input v-model="importText" type="textarea" :rows="12" placeholder='粘贴导出的 JSON 数组'/>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" @click="importSubs" :loading="importing">导入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="notifyVisible" title="Telegram 通知设置" width="520">
      <el-form label-width="140">
        <el-form-item label="Bot Token">
          <el-input v-model="notifyForm.botToken" placeholder="123456:ABC-...,留空关闭通知"/>
        </el-form-item>
        <el-form-item label="Chat ID">
          <el-input v-model="notifyForm.chatId" placeholder="与 bot 对话后获取"/>
        </el-form-item>
        <el-form-item label="完结归档(天)">
          <el-input-number v-model="notifyForm.archiveDays" :min="0" :max="3650"/>
          <span class="sub-text" style="margin-left:8px">完结 N 天后自动释放转存文件,0=关闭</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="notifyVisible = false">取消</el-button>
        <el-button type="primary" @click="saveNotify">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from 'axios'
import {ElMessage, ElMessageBox} from 'element-plus'

interface SubscriptionDto {
  id: number
  name: string
  keyword: string
  season: number | null
  doubanId: number | null
  metaProvider: string | null
  metaId: string | null
  officialEpisodes: number | null
  officialTotal: number | null
  officialStatus: string | null
  nextAirTime: number | null
  cover: string | null
  mode: string
  status: string
  accountId: number | null
  accountIds: number[] | null
  expectedEpisodes: number | null
  currentEpisodes: number | null
  lastEpisode: number | null
  missingEpisodes: number[]
  stallCount: number
  checkIntervalHours: number | null
  nextCheckTime: number | null
  lastCheckTime: number | null
  resourceCount: number
  gapCount: number
  activeResourceTitle: string | null
  mountPath: string | null
  filter: Filter | null
}

interface Filter {
  driveTypes: number[] | null
  qualities: string[] | null
  includeKeywords: string[] | null
  excludeKeywords: string[] | null
  minEpisodeSizeMb: number | null
  maxEpisodeSizeMb: number | null
}

interface ResourceDto {
  id: number
  link: string
  type: number | null
  driveName: string | null
  title: string | null
  episodesFound: number | null
  score: number | null
  validity: string | null
  active: boolean
  gap: boolean
}

interface EventDto {
  id: number
  type: string
  detail: string | null
  createdTime: number
}

const driveOptions = [
  {value: 5, label: '夸克'},
  {value: 8, label: '115'},
  {value: 0, label: '阿里'},
  {value: 7, label: 'UC'},
  {value: 9, label: '天翼'},
  {value: 10, label: '百度'},
  {value: 3, label: '123云盘'},
  {value: 2, label: '迅雷'},
  {value: 1, label: 'PikPak'},
  {value: 12, label: '光鸭'},
]

const subscriptions = ref<SubscriptionDto[]>([])
const stats = ref<any>(null)
const inboxItems = ref<any[]>([])
const inboxExpanded = ref(false)
const loading = ref(false)
const tableRef = ref<any>(null)
const selected = ref<any[]>([])
const formVisible = ref(false)
const saving = ref(false)
const form = ref<any>({})
const metaProvider = ref('douban')
const metaKeyword = ref('')
const metaSearching = ref(false)
const metaResults = ref<any[]>([])
const metaLink = ref('')
const resolvingLink = ref(false)
const accounts = ref<any[]>([])
const previewVisible = ref(false)
const previewing = ref(false)
const previewItems = ref<any[]>([])
const resourcesVisible = ref(false)
const resourcesLoading = ref(false)
const resources = ref<ResourceDto[]>([])
const episodesVisible = ref(false)
const episodesLoading = ref(false)
const episodeItems = ref<any[]>([])
const eventsVisible = ref(false)
const events = ref<EventDto[]>([])
const current = ref<SubscriptionDto | null>(null)
const importVisible = ref(false)
const importText = ref('')
const importing = ref(false)
const notifyVisible = ref(false)
const notifyForm = ref({botToken: '', chatId: '', archiveDays: 0})

onMounted(() => {
  loadAll()
  axios.get('/api/pan/accounts').then(response => {
    accounts.value = response.data || []
  }).catch(() => {
  })
})

const loadAll = () => {
  loading.value = true
  axios.get('/api/media-subscriptions').then(response => {
    subscriptions.value = response.data
  }).finally(() => {
    loading.value = false
  })
  axios.get('/api/media-subscriptions/stats').then(response => {
    stats.value = response.data
  })
  axios.get('/api/media-subscriptions/inbox').then(response => {
    inboxItems.value = response.data || []
  })
}

const handleAdd = () => {
  form.value = {
    name: '',
    keyword: '',
    season: 1,
    doubanId: null,
    metaProvider: null,
    metaId: null,
    expectedEpisodes: null,
    mode: 'FOLLOW',
    accountId: null,
    accountIds: [],
    checkIntervalHours: 6,
    driveTypes: [],
    qualities: [],
    includeKeywords: [],
    excludeKeywords: [],
    minEpisodeSizeMb: 20,
    maxEpisodeSizeMb: 0,
  }
  metaKeyword.value = ''
  metaResults.value = []
  formVisible.value = true
}

const handleEdit = (row: SubscriptionDto) => {
  form.value = {
    id: row.id,
    name: row.name,
    keyword: row.keyword,
    season: row.season ?? 1,
    doubanId: row.doubanId,
    metaProvider: row.metaProvider,
    metaId: row.metaId,
    expectedEpisodes: row.expectedEpisodes,
    mode: row.mode,
    accountId: null,
    accountIds: row.accountIds || (row.accountId ? [row.accountId] : []),
    checkIntervalHours: row.checkIntervalHours ?? 6,
    driveTypes: row.filter?.driveTypes || [],
    qualities: row.filter?.qualities || [],
    includeKeywords: row.filter?.includeKeywords || [],
    excludeKeywords: row.filter?.excludeKeywords || [],
    minEpisodeSizeMb: row.filter?.minEpisodeSizeMb ?? 20,
    maxEpisodeSizeMb: row.filter?.maxEpisodeSizeMb ?? 0,
  }
  metaProvider.value = row.metaProvider || 'douban'
  metaKeyword.value = ''
  metaResults.value = []
  formVisible.value = true
}

const resolveLink = () => {
  if (!metaLink.value) return
  resolvingLink.value = true
  axios.get('/api/media-subscriptions/meta/resolve', {params: {url: metaLink.value.trim()}})
      .then(response => {
        const data = response.data
        form.value.metaProvider = data.provider
        form.value.metaId = data.id
        if (data.provider === 'douban' && data.doubanId) {
          form.value.doubanId = data.doubanId
        }
        if (data.season) {
          form.value.season = data.season
        }
        if (!form.value.name && data.name) {
          form.value.name = data.name
        }
        if (data.totalEpisodes && !form.value.expectedEpisodes) {
          form.value.expectedEpisodes = data.totalEpisodes
        }
        ElMessage.success(`已识别 ${providerName(data.provider)} 条目${data.name ? ':' + data.name : ''}`)
        metaLink.value = ''
      }).finally(() => {
    resolvingLink.value = false
  })
}

const searchMeta = () => {
  if (!metaKeyword.value) return
  metaSearching.value = true
  axios.get('/api/media-subscriptions/meta/search', {params: {keyword: metaKeyword.value, provider: metaProvider.value}})
      .then(response => {
        metaResults.value = response.data.items || []
        const errors = response.data.errors || {}
        const failed = Object.entries(errors).map(([provider, message]) => `${providerName(provider)}:${message}`)
        if (failed.length) ElMessage.warning(`部分源失败 - ${failed.join(';')}`)
        if (!metaResults.value.length && !failed.length) ElMessage.info('该源未找到,可换源或留空')
      }).finally(() => {
    metaSearching.value = false
  })
}

const selectMeta = (item: any) => {
  if (form.value.metaProvider === item.provider && form.value.metaId === item.id) {
    form.value.metaProvider = null
    form.value.metaId = null
  } else {
    form.value.metaProvider = item.provider
    form.value.metaId = item.id
  }
  if (form.value.metaId && !form.value.name) {
    form.value.name = item.name
  }
}

const providerName = (provider: string) => {
  return {douban: '豆瓣', tmdb: 'TMDB', bangumi: 'Bangumi', official: '官方平台'}[provider] || provider || ''
}

const buildBody = () => ({
  name: form.value.name,
  keyword: form.value.keyword,
  season: form.value.season,
  doubanId: form.value.doubanId,
  metaProvider: form.value.metaProvider,
  metaId: form.value.metaId,
  expectedEpisodes: form.value.expectedEpisodes,
  mode: form.value.mode,
  accountId: form.value.accountId,
  accountIds: form.value.accountIds,
  checkIntervalHours: form.value.checkIntervalHours,
  filter: {
    driveTypes: form.value.driveTypes,
    qualities: form.value.qualities,
    includeKeywords: form.value.includeKeywords,
    excludeKeywords: form.value.excludeKeywords,
    minEpisodeSizeMb: form.value.minEpisodeSizeMb,
    maxEpisodeSizeMb: form.value.maxEpisodeSizeMb,
  },
})

const save = () => {
  if (!form.value.name) {
    ElMessage.warning('请填写剧名')
    return
  }
  if (form.value.mode === 'TRANSFER' && !(form.value.accountIds || []).length && !form.value.accountId) {
    ElMessage.warning('转存模式需选择至少一个网盘账号')
    return
  }
  saving.value = true
  const body = buildBody()
  const request = form.value.id
      ? axios.post(`/api/media-subscriptions/${form.value.id}`, body)
      : axios.post('/api/media-subscriptions', body)
  request.then(() => {
    ElMessage.success(form.value.id ? '已保存' : '已创建,开始首次搜索(稍后刷新查看结果)')
    formVisible.value = false
    setTimeout(loadAll, 3000)
  }).finally(() => {
    saving.value = false
  })
}

const preview = () => {
  const keyword = form.value.keyword || form.value.name
  if (!keyword) {
    ElMessage.warning('请先填写剧名或搜索词')
    return
  }
  previewing.value = true
  previewVisible.value = true
  previewItems.value = []
  axios.post('/api/media-subscriptions/preview', buildBody()).then(response => {
    previewItems.value = response.data
    if (!response.data.length) ElMessage.info('无匹配候选,试试更换关键词')
  }).finally(() => {
    previewing.value = false
  })
}

const handleDelete = (row: SubscriptionDto) => {
  ElMessageBox.confirm(`确定删除订阅「${row.name}」?挂载与候选资源将一并清理${row.mode === 'TRANSFER' ? '(已转存文件保留)' : ''}。`, '删除订阅', {type: 'warning'})
      .then(() => axios.delete(`/api/media-subscriptions/${row.id}`))
      .then(() => {
        ElMessage.success('已删除')
        loadAll()
      }).catch(() => {
  })
}

const checkNow = (row: SubscriptionDto) => {
  axios.post(`/api/media-subscriptions/${row.id}/check`).then(() => {
    ElMessage.success('已开始检查,稍后刷新查看结果')
    setTimeout(loadAll, 6000)
  })
}

const transferNow = (row: SubscriptionDto) => {
  axios.post(`/api/media-subscriptions/${row.id}/transfer`).then(() => {
    ElMessage.success('已开始增量转存,结果见动态')
    setTimeout(loadAll, 15000)
  })
}

const togglePause = (row: SubscriptionDto) => {
  const action = row.status === 'PAUSED' ? 'resume' : 'pause'
  axios.post(`/api/media-subscriptions/${row.id}/${action}`).then(loadAll)
}

const subscribeNextSeason = (row: SubscriptionDto) => {
  axios.get(`/api/media-subscriptions/${row.id}/next-season`).then(response => {
    if (!response.data.available) {
      ElMessage.info(response.data.reason || '暂未发现下一季')
      return
    }
    const season = response.data.season
    ElMessageBox.confirm(`发现第 ${season} 季,立即订阅?`, '多季联动', {type: 'info'})
        .then(() => axios.post('/api/media-subscriptions', {
          name: row.name,
          keyword: row.name,
          season: season,
          metaProvider: row.metaProvider,
          metaId: row.metaId,
          mode: 'FOLLOW',
          filter: row.filter,
        }))
        .then(() => {
          ElMessage.success(`已订阅第 ${season} 季`)
          loadAll()
        }).catch(() => {
    })
  })
}

const showResources = (row: SubscriptionDto) => {
  current.value = row
  resourcesVisible.value = true
  loadResources()
}

const loadResources = () => {
  if (!current.value) return
  resourcesLoading.value = true
  axios.get(`/api/media-subscriptions/${current.value.id}/resources`).then(response => {
    resources.value = response.data
  }).finally(() => {
    resourcesLoading.value = false
  })
}

const activateResource = (resource: ResourceDto) => {
  if (!current.value) return
  axios.post(`/api/media-subscriptions/${current.value.id}/resources/${resource.id}/activate`).then(() => {
    ElMessage.success('已开始换源,稍后刷新')
    setTimeout(loadResources, 6000)
    setTimeout(loadAll, 8000)
  })
}

const showEpisodes = (row: SubscriptionDto) => {
  current.value = row
  episodesVisible.value = true
  episodesLoading.value = true
  axios.get(`/api/media-subscriptions/${row.id}/episodes`).then(response => {
    episodeItems.value = response.data
  }).finally(() => {
    episodesLoading.value = false
  })
}

const showEvents = (row: SubscriptionDto) => {
  current.value = row
  eventsVisible.value = true
  axios.get(`/api/media-subscriptions/${row.id}/events`).then(response => {
    events.value = response.data
  })
}

const selectAll = () => {
  subscriptions.value.forEach(row => tableRef.value?.toggleRowSelection(row, true))
}
const selectNone = () => {
  tableRef.value?.clearSelection()
}
const invertSelection = () => {
  subscriptions.value.forEach(row => tableRef.value?.toggleRowSelection(row, !selected.value.includes(row)))
}

const batch = (action: string) => {
  const ids = selected.value.map(row => row.id)
  if (action === 'delete') {
    ElMessageBox.confirm(`批量删除 ${ids.length} 个订阅?`, '批量删除', {type: 'warning'})
        .then(() => doBatch(action, ids))
        .catch(() => {
        })
  } else {
    doBatch(action, ids)
  }
}

const doBatch = (action: string, ids: number[]) => {
  axios.post('/api/media-subscriptions/batch', {action, ids}).then(response => {
    ElMessage.success(`已对 ${response.data.affected} 个订阅执行操作`)
    setTimeout(loadAll, action === 'check' ? 6000 : 500)
  })
}

const exportSubs = () => {
  axios.get('/api/media-subscriptions/export').then(response => {
    const blob = new Blob([JSON.stringify(response.data, null, 2)], {type: 'application/json'})
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'media-subscriptions.json'
    link.click()
    URL.revokeObjectURL(url)
  })
}

const importSubs = () => {
  try {
    const list = JSON.parse(importText.value)
    importing.value = true
    axios.post('/api/media-subscriptions/import', list).then(response => {
      ElMessage.success(`导入完成:新建 ${response.data.created},跳过重复 ${response.data.skipped}`)
      importVisible.value = false
      importText.value = ''
      loadAll()
    }).finally(() => {
      importing.value = false
    })
  } catch (e) {
    ElMessage.error('JSON 解析失败')
  }
}

const openNotify = () => {
  axios.get('/api/settings').then(response => {
    const settings = response.data || {}
    notifyForm.value.botToken = settings['msub_telegram_bot_token'] || ''
    notifyForm.value.chatId = settings['msub_telegram_chat_id'] || ''
    notifyForm.value.archiveDays = parseInt(settings['msub_archive_days'] || '0') || 0
    notifyVisible.value = true
  }).catch(() => {
    notifyVisible.value = true
  })
}

const saveNotify = () => {
  const saves = [
    axios.post('/api/settings', {name: 'msub_telegram_bot_token', value: notifyForm.value.botToken}),
    axios.post('/api/settings', {name: 'msub_telegram_chat_id', value: notifyForm.value.chatId}),
    axios.post('/api/settings', {name: 'msub_archive_days', value: String(notifyForm.value.archiveDays)}),
  ]
  Promise.all(saves).then(() => {
    ElMessage.success('已保存')
    notifyVisible.value = false
  })
}

const statusText = (status: string) => {
  switch (status) {
    case 'ACTIVE':
      return '追更中'
    case 'PAUSED':
      return '已暂停'
    case 'ENDED':
      return '已完结'
    case 'ERROR':
      return '异常'
    default:
      return status
  }
}

const statusType = (status: string) => {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'PAUSED':
      return 'info'
    case 'ENDED':
      return ''
    case 'ERROR':
      return 'danger'
    default:
      return 'info'
  }
}

const validityType = (validity: string | null) => {
  if (validity === 'OK') return 'success'
  if (validity === 'BAD') return 'danger'
  return 'info'
}

const eventType = (type: string) => {
  switch (type) {
    case 'NEW_EPISODE':
      return 'success'
    case 'GAP_FILLED':
      return 'warning'
    case 'SOURCE_INVALID':
    case 'ERROR':
    case 'TRANSFER_FAILED':
      return 'danger'
    case 'SOURCE_REPLACED':
      return 'primary'
    default:
      return 'info'
  }
}

const eventTypeName = (type: string) => {
  const names: Record<string, string> = {
    NEW_EPISODE: '新集更新',
    SOURCE_INVALID: '主源失效',
    SOURCE_REPLACED: '换源',
    GAP_FILLED: '补缺',
    POOL_FILLED: '候选池',
    TRANSFER_DONE: '转存完成',
    TRANSFER_FAILED: '转存失败',
    UPGRADE_AVAILABLE: '升级提醒',
    ARCHIVED: '归档',
    ERROR: '异常',
    ENDED: '完结',
  }
  return names[type] || type
}

const eventDetail = (event: EventDto) => {
  return eventTypeName(event.type) + ':' + (event.detail || '')
}

const compactNumbers = (numbers: number[]) => {
  if (numbers.length <= 6) return numbers.join(',')
  return numbers.slice(0, 6).join(',') + ` 等${numbers.length}集`
}

const formatTime = (time: number | null) => {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN', {hour12: false})
}
</script>

<style scoped>
.stats-row {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.stat-card {
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 18px;
  min-width: 90px;
  text-align: center;
}

.stat-card.danger .stat-value {
  color: var(--el-color-danger);
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.inbox-card {
  margin-bottom: 12px;
  padding: 10px 16px;
}

.inbox-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
}

.inbox-timeline {
  margin-top: 12px;
  max-height: 300px;
  overflow-y: auto;
}

.batch-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 10px;
}

.name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cover {
  width: 40px;
  height: 56px;
  border-radius: 4px;
  flex-shrink: 0;
}

.cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-secondary);
}

.sub-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.sub-text.danger {
  color: var(--el-color-danger);
}

.meta-search {
  display: flex;
  gap: 8px;
  width: 100%;
}

.meta-results {
  margin-top: 8px;
  max-height: 240px;
  overflow-y: auto;
  width: 100%;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px;
  cursor: pointer;
  border-radius: 4px;
}

.meta-item:hover {
  background: var(--el-fill-color-light);
}

.meta-item.selected {
  background: var(--el-color-primary-light-9);
  outline: 1px solid var(--el-color-primary-light-7);
}

.meta-cover {
  width: 27px;
  height: 38px;
  border-radius: 2px;
  background: var(--el-fill-color-dark);
  flex-shrink: 0;
}

.meta-info {
  font-size: 13px;
}
</style>
