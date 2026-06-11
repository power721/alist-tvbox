<template>
  <div class="sub-config-editor">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- 站点 -->
      <el-tab-pane label="站点" name="sites">
        <el-form-item label="过滤模式" v-if="mode === 'subscription'">
          <el-radio-group v-model="state.filterMode">
            <el-radio label="none">继承全局</el-radio>
            <el-radio label="whitelist">白名单</el-radio>
            <el-radio label="blacklist">黑名单</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="过滤模式" v-else>
          <el-radio-group v-model="state.filterMode">
            <el-radio label="none">不过滤</el-radio>
            <el-radio label="whitelist">白名单</el-radio>
            <el-radio label="blacklist">黑名单</el-radio>
          </el-radio-group>
        </el-form-item>

        <div style="margin-bottom: 8px">
          <el-button type="primary" plain @click="openSiteForm">+ 添加自定义站点</el-button>
        </div>

        <el-alert v-if="catalogError" type="warning" :closable="false" :title="catalogError" style="margin-bottom: 8px" />

        <el-collapse v-if="siteGroups.length" v-model="expandedGroups">
          <el-collapse-item v-for="group in siteGroups" :key="group.key" :name="group.key">
            <template #title>{{ group.label }}（{{ group.rows.length }}）</template>
            <el-table :data="group.rows" border style="width: 100%">
              <el-table-column :label="filterCheckboxLabel" width="60" v-if="state.filterMode !== 'none'">
                <template #default="scope">
                  <el-checkbox v-model="scope.row.enabled" />
                </template>
              </el-table-column>
              <el-table-column prop="key" label="key" width="180" />
              <el-table-column label="名称">
                <template #default="scope">
                  <el-input v-model="scope.row.name" :disabled="!isOwnRow(scope.row)" />
                </template>
              </el-table-column>
              <el-table-column label="排序" width="120">
                <template #default="scope">
                  <el-input v-model="scope.row.order" :disabled="!isOwnRow(scope.row)" placeholder="默认" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="scope">
                  <el-button v-if="scope.row.isCustom" link type="danger" @click="removeCustomSite(scope.row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <el-empty v-else description="无站点目录,可手动添加自定义站点" />

        <el-button type="primary" plain @click="openSiteForm" style="margin-top: 8px">+ 添加自定义站点</el-button>
      </el-tab-pane>

      <!-- 解析 -->
      <el-tab-pane label="解析" name="parses">
        <el-table v-if="parseRows.length" :data="parseRows" border style="width: 100%">
          <el-table-column label="启用" width="60">
            <template #default="scope">
              <el-checkbox v-model="scope.row.enabled" :disabled="scope.row.isCustom" />
            </template>
          </el-table-column>
          <el-table-column label="名称">
            <template #default="scope">
              <el-input v-model="scope.row.name" :disabled="!scope.row.isCustom" />
            </template>
          </el-table-column>
          <el-table-column label="类型" width="170">
            <template #default="scope">
              <el-select v-if="scope.row.isCustom" v-model="scope.row.type">
                <el-option v-for="o in parseTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="scope">
              <el-button v-if="scope.row.isCustom" link type="danger" @click="removeCustomParse(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="无解析目录" />
        <el-button type="primary" plain @click="openParseForm" style="margin-top: 8px">+ 添加自定义解析</el-button>
      </el-tab-pane>

      <!-- 基础 -->
      <el-tab-pane label="基础" name="basic">
        <el-form label-width="120">
          <el-form-item label="壁纸 wallpaper">
            <el-input v-model="state.wallpaper" placeholder="壁纸图片/接口 URL" />
          </el-form-item>
          <el-form-item label="Logo">
            <el-input v-model="state.logo" placeholder="Logo 地址" />
          </el-form-item>
          <el-form-item label="播放标识 flags">
            <el-select v-model="state.flags" multiple filterable allow-create default-first-option placeholder="追加到上游" style="width: 100%" />
          </el-form-item>
          <el-form-item label="广告 ads">
            <el-select v-model="state.ads" multiple filterable allow-create default-first-option placeholder="广告域名(追加)" style="width: 100%" />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- Headers -->
      <el-tab-pane label="Headers" name="headers">
        <div v-if="state.headers.length">
          <div v-for="(h, hi) in state.headers" :key="hi" style="margin-bottom: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 8px">
            <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px">
              <span style="white-space: nowrap; font-weight: 500">Host</span>
              <el-input v-model="h.host" placeholder="example.com" style="flex: 1" />
              <el-button link type="danger" @click="removeHeader(hi)">删除组</el-button>
            </div>
            <el-table :data="h.pairs" border size="small">
              <el-table-column label="Name" width="200">
                <template #default="scope">
                  <el-input v-model="scope.row.name" placeholder="Referer" />
                </template>
              </el-table-column>
              <el-table-column label="Value">
                <template #default="scope">
                  <el-input v-model="scope.row.value" placeholder="https://example.com/" />
                </template>
              </el-table-column>
              <el-table-column label="" width="50">
                <template #default="scope">
                  <el-button link type="danger" @click="h.pairs.splice(scope.$index, 1)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button type="primary" link @click="h.pairs.push({ name: '', value: '' })" style="margin-top: 4px">+ 添加</el-button>
          </div>
        </div>
        <el-empty v-else description="无 Headers 配置" />
        <el-button type="primary" plain @click="addHeader" style="margin-top: 8px">+ 添加 Header 组</el-button>
      </el-tab-pane>

      <!-- 直播 -->
      <el-tab-pane label="直播" name="lives">
        <div v-if="state.lives.length">
          <div v-for="(l, li) in state.lives" :key="li" style="margin-bottom: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 12px">
            <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px">
              <el-input v-model="l.name" placeholder="名称" style="width: 160px" />
              <el-select v-model="l.type" style="width: 100px">
                <el-option :value="0" label="接口 0" />
                <el-option :value="1" label="标准 1" />
              </el-select>
              <el-select v-model="l.playerType" style="width: 120px">
                <el-option :value="0" label="系统 0" />
                <el-option :value="1" label="IJK 1" />
                <el-option :value="2" label="Exo 2" />
              </el-select>
              <el-button link type="danger" @click="state.lives.splice(li, 1)">删除</el-button>
            </div>
            <el-form label-width="80" size="small">
              <el-form-item label="URL"><el-input v-model="l.url" placeholder="直播源地址" /></el-form-item>
              <el-form-item label="UA"><el-input v-model="l.ua" placeholder="okhttp/3.15" /></el-form-item>
              <el-form-item label="EPG"><el-input v-model="l.epg" placeholder="http://epg.example.com/?ch={name}&date={date}" /></el-form-item>
              <el-form-item label="Logo"><el-input v-model="l.logo" placeholder="http://logo.example.com/{name}.png" /></el-form-item>
            </el-form>
          </div>
        </div>
        <el-empty v-else description="无直播配置" />
        <el-button type="primary" plain @click="addLive" style="margin-top: 8px">+ 添加直播源</el-button>
      </el-tab-pane>

      <!-- 原始 JSON -->
      <el-tab-pane label="原始JSON" name="json">
        <el-input v-model="jsonText" type="textarea" :rows="18" />
        <el-alert v-if="jsonError" type="error" :closable="false" :title="jsonError" style="margin-top: 8px" />
        <div style="margin-top: 8px">
          <el-button @click="applyJson">从 JSON 应用到表单</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 自定义站点表单 -->
    <el-dialog v-model="siteFormVisible" title="自定义站点" width="640px" append-to-body destroy-on-close>
      <el-form :model="siteForm" label-width="120" style="max-height: 60vh; overflow-y: auto">
        <el-form-item label="key" required><el-input v-model="siteForm.key" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="siteForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="siteForm.type">
            <el-option v-for="o in siteTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="api"><el-input v-model="siteForm.api" /></el-form-item>
        <el-form-item label="ext"><el-input v-model="siteForm.ext" type="textarea" :rows="3" placeholder="字符串或 JSON 对象" /></el-form-item>
        <el-form-item label="jar"><el-input v-model="siteForm.jar" /></el-form-item>
        <el-form-item label="searchable">
          <el-select v-model="siteForm.searchable">
            <el-option :value="0" label="不可搜索(0)" />
            <el-option :value="1" label="可搜索(1)" />
            <el-option :value="2" label="聚合(2)" />
          </el-select>
        </el-form-item>
        <el-form-item label="quickSearch"><el-switch v-model="siteForm.quickSearch" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="filterable"><el-switch v-model="siteForm.filterable" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="changeable"><el-switch v-model="siteForm.changeable" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="风格 style">
          <el-select v-model="siteForm.styleType" style="width: 140px">
            <el-option value="" label="默认" />
            <el-option value="rect" label="rect" />
            <el-option value="oval" label="oval" />
            <el-option value="list" label="list" />
          </el-select>
          <el-input v-model="siteForm.styleRatio" placeholder="ratio" style="width: 120px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="排序 order"><el-input v-model="siteForm.order" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="siteFormVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSiteForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 自定义解析表单 -->
    <el-dialog v-model="parseFormVisible" title="自定义解析" width="560px" append-to-body destroy-on-close>
      <el-form :model="parseForm" label-width="100">
        <el-form-item label="名称" required><el-input v-model="parseForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="parseForm.type">
            <el-option v-for="o in parseTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="url"><el-input v-model="parseForm.url" /></el-form-item>
        <el-form-item label="flag">
          <el-select v-model="parseForm.flag" multiple filterable allow-create default-first-option style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="parseFormVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmParseForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import {
  parseOverride,
  detectFilterMode,
  disabledSiteKeys,
  whitelistKeys,
  disabledParseNames,
  siteOverrideMap,
  serialize,
  stringify,
  buildHeaderRows,
  buildLiveRows,
} from '@/utils/subscriptionConfig.mjs'

const props = withDefaults(
  defineProps<{
    modelValue: string
    mode?: 'subscription' | 'global'
    referenceSid?: string
    token?: string
  }>(),
  { mode: 'subscription', referenceSid: '', token: '' }
)
defineEmits<{ 'update:modelValue': [string] }>()

const siteTypeOptions = [
  { value: 0, label: 'CMS(xml) 0' },
  { value: 1, label: 'CMS(json) 1' },
  { value: 3, label: 'Spider 3' },
  { value: 4, label: '外部 4' },
]
const parseTypeOptions = [
  { value: 0, label: '嗅探 0' },
  { value: 1, label: 'Json 1' },
  { value: 2, label: 'Json扩展 2' },
  { value: 3, label: '聚合 3' },
  { value: 4, label: '超级解析 4' },
]

const activeTab = ref('sites')
const catalogError = ref('')
const jsonText = ref('')
const jsonError = ref('')
const siteFormVisible = ref(false)
const parseFormVisible = ref(false)

const state = reactive<any>({
  filterMode: 'none',
  sites: [],
  parses: [],
  wallpaper: '',
  logo: '',
  flags: [],
  ads: [],
  headers: [],
  lives: [],
})
// 未建模键的保留载体
let baseConfig: Record<string, any> = {}

const filterCheckboxLabel = '保留'
const siteRows = ref<any[]>([])
const parseRows = ref<any[]>([])
const expandedGroups = ref<string[]>(['upstream', 'custom'])
const GROUP_ORDER = [
  { key: 'upstream', label: '上游源' },
  { key: 'custom', label: '自定义站点' },
  { key: 'builtin', label: '内置源' },
  { key: 'plugin', label: '插件源' },
]
const groupKeyOf = (row: any) =>
  row.isCustom ? 'custom' : row.origin === 'builtin' ? 'builtin' : row.origin === 'plugin' ? 'plugin' : 'upstream'
const isOwnRow = (row: any) => row.origin === 'upstream' || row.isCustom
const siteGroups = computed(() =>
  GROUP_ORDER.map((g) => ({ ...g, rows: siteRows.value.filter((r) => groupKeyOf(r) === g.key) })).filter(
    (g) => g.rows.length
  )
)

const siteForm = reactive<any>({})
const parseForm = reactive<any>({})

function resetSiteForm() {
  Object.assign(siteForm, {
    key: '', name: '', type: 3, api: '', ext: '', jar: '',
    searchable: 1, quickSearch: 1, filterable: 1, changeable: 0,
    styleType: '', styleRatio: '', order: '',
  })
}
function resetParseForm() {
  Object.assign(parseForm, { name: '', type: 0, url: '', flag: [] })
}

const load = async () => {
  const parsed = parseOverride(props.modelValue)
  baseConfig = parsed === null ? {} : JSON.parse(JSON.stringify(parsed))
  if (parsed === null) {
    jsonError.value = '原始内容不是合法 JSON,已切到 JSON 标签'
    jsonText.value = props.modelValue
    activeTab.value = 'json'
  }
  const config = parsed === null ? {} : parsed

  state.filterMode = detectFilterMode(config)
  state.wallpaper = config.wallpaper || ''
  state.logo = config.logo || ''
  state.flags = Array.isArray(config.flags) ? [...config.flags] : []
  state.ads = Array.isArray(config.ads) ? [...config.ads] : []
  state.headers = buildHeaderRows(config)
  state.lives = buildLiveRows(config)

  // catalog
  let catalog: any = { sites: [], parses: [] }
  catalogError.value = ''
  if (props.referenceSid !== '' && props.referenceSid != null) {
    try {
      const { data } = await axios.get(`/api/subscriptions/${props.referenceSid}/catalog`)
      catalog = data
    } catch {
      catalogError.value = '获取站点目录失败,可手动添加自定义站点'
    }
  }
  buildRows(config, catalog)
}

function buildRows(config: Record<string, any>, catalog: any) {
  const disabled = new Set(disabledSiteKeys(config))
  const white = new Set(whitelistKeys(config))
  const overrides = siteOverrideMap(config)
  const catalogKeys = new Set<string>((catalog.sites || []).map((s: any) => String(s.key)))

  const rows: any[] = []
  for (const c of catalog.sites || []) {
    const key = String(c.key)
    const ov = overrides[key] || {}
    rows.push({
      key,
      origin: c.origin,
      isCustom: false,
      enabled: state.filterMode === 'whitelist' ? white.has(key) : !disabled.has(key),
      name: ov.name != null ? ov.name : c.name,
      originalName: c.name,
      hadNameOverride: ov.name != null,
      order: ov.order != null ? ov.order : '',
    })
  }
  // catalog 缺失但被禁用/白名单引用的 key -> 合成行
  const known = new Set(rows.map((r) => r.key))
  ;[...disabled, ...white].forEach((key) => {
    if (!known.has(key)) {
      const ov = overrides[key] || {}
      rows.push({
        key, origin: 'upstream', isCustom: false,
        enabled: state.filterMode === 'whitelist' ? white.has(key) : !disabled.has(key),
        name: ov.name != null ? ov.name : key, originalName: key, hadNameOverride: ov.name != null,
        order: ov.order != null ? ov.order : '',
      })
      known.add(key)
    }
  })
  // 自定义站点(config.sites 中 key ∉ catalog 且为完整对象)
  if (Array.isArray(config.sites)) {
    for (const s of config.sites) {
      const key = s && s.key != null ? String(s.key) : ''
      if (!key || catalogKeys.has(key) || known.has(key)) continue
      if (s.type != null || s.api != null) {
        rows.push({
          ...s, key, origin: 'custom', isCustom: true, enabled: true,
          styleType: s.style?.type || '', styleRatio: s.style?.ratio ?? '',
        })
        known.add(key)
      }
    }
  }
  siteRows.value = rows
  state.sites = rows

  // 解析
  const disabledParses = new Set(disabledParseNames(config))
  const prows: any[] = []
  for (const p of catalog.parses || []) {
    prows.push({ name: p.name, isCustom: false, enabled: !disabledParses.has(p.name) })
  }
  if (Array.isArray(config.parses)) {
    for (const p of config.parses) {
      if (p && p.name) {
        prows.push({
          name: p.name, isCustom: true, enabled: true, type: p.type ?? 0,
          url: p.url || '', flag: p.ext?.flag || [], header: p.ext?.header || {},
        })
      }
    }
  }
  parseRows.value = prows
  state.parses = prows
}

function openSiteForm() {
  resetSiteForm()
  siteFormVisible.value = true
}
function confirmSiteForm() {
  if (!siteForm.key) {
    ElMessage.warning('请输入 key')
    return
  }
  const row: any = {
    key: siteForm.key, name: siteForm.name, type: siteForm.type, api: siteForm.api,
    ext: siteForm.ext, jar: siteForm.jar, searchable: siteForm.searchable,
    quickSearch: siteForm.quickSearch, filterable: siteForm.filterable, changeable: siteForm.changeable,
    order: siteForm.order, origin: 'custom', isCustom: true, enabled: true,
  }
  if (siteForm.styleType) row.style = { type: siteForm.styleType, ratio: Number(siteForm.styleRatio) || undefined }
  if (typeof row.ext === 'string' && row.ext.trim().startsWith('{')) {
    try { row.ext = JSON.parse(row.ext) } catch { /* keep string */ }
  }
  siteRows.value.push(row)
  state.sites = siteRows.value
  siteFormVisible.value = false
}
function removeCustomSite(row: any) {
  siteRows.value = siteRows.value.filter((r) => r !== row)
  state.sites = siteRows.value
}

function openParseForm() {
  resetParseForm()
  parseFormVisible.value = true
}
function confirmParseForm() {
  if (!parseForm.name) {
    ElMessage.warning('请输入名称')
    return
  }
  parseRows.value.push({
    name: parseForm.name, type: parseForm.type, url: parseForm.url,
    flag: [...parseForm.flag], header: {}, isCustom: true, enabled: true,
  })
  state.parses = parseRows.value
  parseFormVisible.value = false
}
function removeCustomParse(row: any) {
  parseRows.value = parseRows.value.filter((r) => r !== row)
  state.parses = parseRows.value
}

function addHeader() {
  state.headers.push({ host: '', pairs: [{ name: '', value: '' }] })
}
function removeHeader(index: number) {
  state.headers.splice(index, 1)
}

function addLive() {
  state.lives.push({ name: '', type: 0, url: '', playerType: 0, ua: '', epg: '', logo: '' })
}

function onTabChange(name: string) {
  if (name === 'json') {
    jsonText.value = JSON.stringify(serialize(baseConfig, state), null, 2)
    jsonError.value = ''
  }
}
function applyJson() {
  const parsed = parseOverride(jsonText.value)
  if (parsed === null) {
    jsonError.value = 'JSON 格式错误'
    return
  }
  baseConfig = JSON.parse(JSON.stringify(parsed))
  jsonError.value = ''
  const catalog = {
    sites: siteRows.value.filter((r) => !r.isCustom).map((r) => ({ key: r.key, name: r.originalName, origin: r.origin })),
    parses: parseRows.value.filter((p) => !p.isCustom).map((p) => ({ name: p.name })),
  }
  state.wallpaper = parsed.wallpaper || ''
  state.logo = parsed.logo || ''
  state.flags = Array.isArray(parsed.flags) ? [...parsed.flags] : []
  state.ads = Array.isArray(parsed.ads) ? [...parsed.ads] : []
  state.headers = buildHeaderRows(parsed)
  state.lives = buildLiveRows(parsed)
  state.filterMode = detectFilterMode(parsed)
  buildRows(parsed, catalog)
  ElMessage.success('已应用到表单')
}

// 对外:保存时取序列化结果。停留在「原始JSON」标签时直接用该 JSON
// (支持清空/直接编辑后保存,无需先点"从 JSON 应用到表单");非法 JSON 返回 null 以阻止保存
function getValue(): string | null {
  if (activeTab.value === 'json') {
    const parsed = parseOverride(jsonText.value)
    if (parsed === null) {
      jsonError.value = 'JSON 格式错误,请修正后再保存'
      return null
    }
    jsonError.value = ''
    return stringify(parsed)
  }
  return stringify(serialize(baseConfig, state))
}
defineExpose({ getValue, reload: load })

watch(
  () => [props.modelValue, props.referenceSid],
  () => load(),
  { immediate: true }
)
</script>

<style scoped>
.sub-config-editor {
  min-height: 360px;
}

/* 限制标签内容高度,内容超出时滚动,标签头保持可见 */
.sub-config-editor :deep(.el-tabs__content) {
  max-height: 55vh;
  overflow-y: auto;
  padding-right: 6px;
}
</style>
