import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentSource = readFileSync(new URL('./PlayConfig.vue', import.meta.url), 'utf8')

test('play config exposes playback sync switch disabled by default with scope selector', () => {
  assert.equal(componentSource.includes(`const playbackSyncEnabled = ref(false)`), true)
  assert.equal(componentSource.includes(`const playbackSyncScope = ref('token')`), true)
  assert.equal(componentSource.includes(`name: 'playback_sync_enabled'`), true)
  assert.equal(componentSource.includes(`data.playback_sync_enabled === 'true'`), true)
  assert.equal(componentSource.includes(`label="播放记录同步"`), true)
  assert.equal(componentSource.includes(`name: 'playback_sync_scope'`), true)
  assert.equal(componentSource.includes(`label="同步分区"`), true)
})

test('play config channel management gates drag sorting by mobile browser detection', () => {
  assert.equal(componentSource.includes(`import {isPluginDragEnabledForUserAgent} from "@/utils/pluginDragSupport.mjs";`), true)
  assert.equal(componentSource.includes('v-if="channelDragEnabled"'), true)
  assert.equal(componentSource.includes('if (!channelDragEnabled) {'), true)
})

test('play config exposes PanSou channel list selector', () => {
  assert.equal(componentSource.includes(`const panSouChannels = ref('custom')`), true)
  assert.equal(componentSource.includes(`{label: '自定义', value: 'custom'}`), true)
  assert.equal(componentSource.includes(`{label: '项目内置', value: 'project'}`), true)
  assert.equal(componentSource.includes(`{label: '盘搜内置', value: 'pansou'}`), true)
  assert.equal(componentSource.includes(`getPanSouChannelCount(item.value)`), true)
  assert.equal(componentSource.includes(`{name: 'pan_sou_channels', value: panSouChannels.value}`), true)
  assert.equal(componentSource.includes(`panSouChannels.value = data.pan_sou_channels || 'custom'`), true)
})

test('play config includes magnet and ed2k in disk order settings', () => {
  assert.equal(componentSource.includes(`const defaultDriverOrder = '9,10,5,7,8,3,2,0,6,1,12,magnet,ed2k,video'.split(',')`), true)
  assert.equal(componentSource.includes(`{label: '光鸭', value: '12'}`), true)
  assert.equal(componentSource.includes(`{label: '光鸭', value: 12}`), true)
  assert.equal(componentSource.includes(`{label: '磁力', value: 'magnet'}`), true)
  assert.equal(componentSource.includes(`{label: 'ED2K', value: 'ed2k'}`), true)
  assert.equal(componentSource.includes(`normalizeDriverOrder(data.tgDriverOrder || '')`), true)
})

test('play config exposes PanSou credentials only when auth is enabled', () => {
  assert.equal(componentSource.includes(`const panSouAuthEnabled = ref(false)`), true)
  assert.equal(componentSource.includes(`panSouAuthEnabled.value = data.auth_enabled === true`), true)
  assert.equal(componentSource.includes(`{name: 'pan_sou_username', value: panSouUsername.value}`), true)
  assert.equal(componentSource.includes(`{name: 'pan_sou_password', value: panSouPassword.value}`), true)
  assert.equal(componentSource.includes(`v-if="panSouUrl && panSouAuthEnabled"`), true)
})

test('play config displays PanSou plugin count', () => {
  assert.equal(componentSource.includes(`const panSouPluginCount = ref(0)`), true)
  assert.equal(componentSource.includes(`panSouPluginCount.value = data.plugin_count || plugins.value.length`), true)
  assert.equal(componentSource.includes(`已启用插件 {{ panSouPluginCount }} 个`), true)
})

test('play config exposes backend PanSou link check settings', () => {
  assert.equal(componentSource.includes(`const panSouLinkCheckEnabled = ref(false)`), true)
  assert.equal(componentSource.includes(`const panSouLinkCheckMaxCount = ref(30)`), true)
  assert.equal(componentSource.includes(`{name: 'pan_sou_link_check_enabled', value: panSouLinkCheckEnabled.value}`), true)
  assert.equal(componentSource.includes(`{name: 'pan_sou_link_check_max_count', value: panSouLinkCheckMaxCount.value}`), true)
  assert.equal(componentSource.includes(`panSouLinkCheckEnabled.value = data.pan_sou_link_check_enabled === 'true'`), true)
  assert.equal(componentSource.includes(`panSouLinkCheckMaxCount.value = +(data.pan_sou_link_check_max_count || 30)`), true)
  assert.equal(componentSource.includes(`label="链接检测"`), true)
  assert.equal(componentSource.includes(`自动检查盘搜搜索结果的有效性`), true)
})

test('play config exposes tg-search api key and health version check', () => {
  assert.equal(componentSource.includes(`const tgSearchApiKey = ref('')`), true)
  assert.equal(componentSource.includes(`const tgSearchVersion = ref('')`), true)
  assert.equal(componentSource.includes(`name: 'tg_search_api_key'`), true)
  assert.equal(componentSource.includes(`axios.get('/api/telegram/tg-search/health')`), true)
  assert.equal(componentSource.includes(`data.version`), true)
})

test('play config links to power721 tg-search deployment guide', () => {
  assert.equal(componentSource.includes(`https://github.com/power721/tg-search`), true)
  assert.equal(componentSource.includes(`TG-Search API Key`), true)
})

test('play config exposes dedicated PanCheck 盘检地址 with fallback hint', () => {
  assert.equal(componentSource.includes(`const panCheckUrl = ref('')`), true)
  assert.equal(componentSource.includes(`panCheckUrl.value = data.pan_check_url`), true)
  assert.equal(componentSource.includes(`name: 'pan_check_url', value: panCheckUrl.value`), true)
  assert.equal(componentSource.includes(`label="盘检地址"`), true)
  assert.equal(componentSource.includes(`https://github.com/Lampon/PanCheck`), true)
})

test('play config exposes TG-Search-only 盘检超时 setting', () => {
  assert.equal(componentSource.includes(`const panCheckTimeoutMs = ref<number | null>(null)`), true)
  assert.equal(componentSource.includes(`panCheckTimeoutMs.value = data.pan_check_timeout_ms ? +data.pan_check_timeout_ms : null`), true)
  assert.equal(componentSource.includes(`name: 'pan_check_timeout_ms', value: panCheckTimeoutMs.value || ''`), true)
  assert.equal(componentSource.includes(`label="盘检超时(ms)"`), true)
})

test('play config exposes PanSou search behavior controls', () => {
  assert.equal(componentSource.includes(`const panSouConc = ref<number | null>(null)`), true)
  assert.equal(componentSource.includes(`const panSouRefresh = ref(false)`), true)
  assert.equal(componentSource.includes(`const panSouRes = ref('merge')`), true)
  assert.equal(componentSource.includes(`{label: '聚合', value: 'merge'}`), true)
  assert.equal(componentSource.includes(`const updatePanSouSearch = () => {`), true)
  assert.equal(componentSource.includes(`{name: 'pan_sou_conc', value: panSouConc.value || ''}`), true)
  assert.equal(componentSource.includes(`panSouRes.value = data.pan_sou_res || 'merge'`), true)
})

test('play config isolates PanSou config in its own tab', () => {
  assert.equal(componentSource.includes(`<el-tab-pane label="盘搜配置" name="pansou">`), true)
  // PanSou address moved out of the basic tab into the dedicated tab
  assert.equal(componentSource.includes(`label="PanSou地址"`), true)
})
