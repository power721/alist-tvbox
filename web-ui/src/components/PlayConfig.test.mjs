import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentSource = readFileSync(new URL('./PlayConfig.vue', import.meta.url), 'utf8')

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
  assert.equal(componentSource.includes(`const defaultDriverOrder = '9,10,5,7,8,3,2,0,6,1,12,magnet,ed2k'.split(',')`), true)
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

test('play config separates public private and telegram management tabs', () => {
  assert.equal(componentSource.includes(`label="公开频道"`), true)
  assert.equal(componentSource.includes(`label="我的频道"`), true)
  assert.equal(componentSource.includes(`label="电报管理"`), true)
  assert.equal(componentSource.includes(`'/api/telegram/private/channels'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/private/channels/sync-list'`), true)
  assert.equal(componentSource.includes(`同步频道列表`), true)
  assert.equal(componentSource.includes(`同步选中频道`), false)
  assert.equal(componentSource.includes(`const syncPrivateChannels = () => {`), false)
})

test('play config highlights changed private channel rows like public channels', () => {
  const rowClassBindings = componentSource.match(/:row-class-name="tableRowClassName"/g) || []

  assert.equal(rowClassBindings.length, 2)
  assert.equal(componentSource.includes(`const markPrivateChannelChanged = (row: PrivateChannel) => {`), true)
  assert.equal(componentSource.includes(`row.changed = true`), true)
  assert.equal(componentSource.includes(`privateChannelsChanged.value = true`), true)
  assert.equal(componentSource.includes(`@change="markPrivateChannelChanged(scope.row)"`), true)
})

test('play config lets desktop users drag private channels to define category order', () => {
  assert.equal(componentSource.includes(`import {computed, onMounted, onUnmounted, ref, watch} from "vue";`), true)
  assert.equal(componentSource.includes(`const activePrivateRows = ref<PrivateChannel[]>([])`), true)
  assert.equal(componentSource.includes(`let privateChannelSortable: Sortable | null = null`), true)
  assert.equal(componentSource.includes(`const privateChannelDragEnabled = computed(() => {`), true)
  assert.equal(componentSource.includes(`return channelDragEnabled && !privateChannelKeyword.value.trim()`), true)
  assert.equal(componentSource.includes(`&& privateChannelWebAccess.value === 'all'`), true)
  assert.equal(componentSource.includes(`const privateRowDrop = () => {`), true)
  assert.equal(componentSource.includes(`if (!privateChannelDragEnabled.value) {`), true)
  assert.equal(componentSource.includes(`privateChannelSortable?.destroy()`), true)
  assert.equal(componentSource.includes(`document.querySelector("#private-channels tbody")`), true)
  assert.equal(componentSource.includes(`activePrivateRows.value = treeToTile(filteredPrivateChannels.value)`), true)
  assert.equal(componentSource.includes(`privateChannels.value = activePrivateRows.value`), true)
  assert.equal(componentSource.includes(`setTimeout(() => privateRowDrop(), 500)`), true)
  assert.equal(componentSource.includes(`watch([privateChannelKeyword, privateChannelVisibility, privateChannelType, privateChannelWebAccess], () => {`), true)
  assert.equal(componentSource.includes(`privateChannelSortable?.destroy()`), true)
  assert.equal(componentSource.includes(`id="private-channels"`), true)
  assert.equal(componentSource.includes(`v-if="privateChannelDragEnabled"`), true)
})

test('play config shows private channel visibility and row sync action', () => {
  const webAccessColumns = componentSource.match(/label="网页访问"/g) || []

  assert.equal(componentSource.includes(`web_access: boolean`), true)
  assert.equal(componentSource.includes(`web_access_checked_at: string`), true)
  assert.equal(componentSource.includes(`const getPrivateChannelVisibilityName = (row: PrivateChannel) => {`), true)
  assert.equal(componentSource.includes(`return row.username ? '公开' : '私密'`), true)
  assert.equal(componentSource.includes(`label="公开状态"`), true)
  assert.equal(componentSource.includes(`{{ getPrivateChannelVisibilityName(scope.row) }}`), true)
  assert.equal(webAccessColumns.length, 2)
  assert.equal(componentSource.includes(`scope.row.web_access`), true)
  assert.equal(componentSource.includes(`'https://t.me/s/'+scope.row.username`), true)
  assert.equal(componentSource.includes(`label="类型"`), true)
  assert.equal(componentSource.includes(`const syncPrivateChannel = (row: PrivateChannel) => {`), true)
  assert.equal(componentSource.includes("axios.post(`/api/telegram/private/channels/${row.id}/sync`)"), true)
  assert.equal(componentSource.includes('已同步 ${data?.messages || 0} 条消息，发现 ${data?.links || 0} 个链接'), true)
  assert.equal(componentSource.includes(`label="操作"`), true)
  assert.equal(componentSource.includes(`@click="syncPrivateChannel(scope.row)"`), true)
  assert.equal(componentSource.includes(`const checkPrivateChannelWebAccess = (row: PrivateChannel) => {`), true)
  assert.equal(componentSource.includes("axios.post(`/api/telegram/private/channels/${row.id}/web-access/check`)"), true)
  assert.equal(componentSource.includes(`@click="checkPrivateChannelWebAccess(scope.row)"`), true)
  assert.equal(componentSource.includes(`:disabled="!scope.row.username"`), true)
  assert.equal(componentSource.includes(`const checkPrivateChannelsWebAccess = () => {`), true)
  assert.equal(componentSource.includes(`'/api/telegram/private/channels/web-access/check'`), true)
  assert.equal(componentSource.includes(`@click="checkPrivateChannelsWebAccess"`), true)
  assert.equal(componentSource.includes(`批量检测`), true)
  assert.equal(
    componentSource.indexOf(`@click="checkPrivateChannelsWebAccess"`) <
      componentSource.indexOf(`@click="savePrivateChannels"`),
    true
  )
})

test('play config filters private channels by keyword visibility and type', () => {
  assert.equal(componentSource.includes(`import {computed, onMounted, onUnmounted, ref, watch} from "vue";`), true)
  assert.equal(componentSource.includes(`const privateChannelKeyword = ref('')`), true)
  assert.equal(componentSource.includes(`const privateChannelVisibility = ref('all')`), true)
  assert.equal(componentSource.includes(`const privateChannelType = ref('all')`), true)
  assert.equal(componentSource.includes(`const privateChannelWebAccess = ref('all')`), true)
  assert.equal(componentSource.includes(`const privateChannelWebAccessOptions = [`), true)
  assert.equal(componentSource.includes(`{label: '全部网页', value: 'all'}`), true)
  assert.equal(componentSource.includes(`{label: '可访问', value: 'accessible'}`), true)
  assert.equal(componentSource.includes(`{label: '不可访问', value: 'inaccessible'}`), true)
  assert.equal(componentSource.includes(`const privateChannelTypeOptions = computed(() => {`), true)
  assert.equal(componentSource.includes(`const filteredPrivateChannels = computed(() => {`), true)
  assert.equal(componentSource.includes(`row.title, row.username`), true)
  assert.equal(componentSource.includes(`row.title, row.username, row.type, row.id, row.telegram_channel_id`), false)
  assert.equal(componentSource.includes(`getPrivateChannelVisibilityValue(row) !== privateChannelVisibility.value`), true)
  assert.equal(componentSource.includes(`row.type !== privateChannelType.value`), true)
  assert.equal(componentSource.includes(`privateChannelWebAccess.value === 'accessible' && !row.web_access`), true)
  assert.equal(componentSource.includes(`privateChannelWebAccess.value === 'inaccessible' && row.web_access`), true)
  assert.equal(componentSource.includes(`<el-row class="private-channel-toolbar" justify="end">`), true)
  assert.equal(componentSource.includes(`class="private-channel-filters"`), false)
  assert.equal(componentSource.includes(`placeholder="搜索频道"`), true)
  assert.equal(componentSource.includes(`v-model="privateChannelVisibility"`), true)
  assert.equal(componentSource.includes(`v-model="privateChannelType"`), true)
  assert.equal(componentSource.includes(`v-model="privateChannelWebAccess"`), true)
  assert.equal(componentSource.includes(`placeholder="网页访问"`), true)
  assert.equal(componentSource.includes(`:data="filteredPrivateChannels"`), true)
})

test('play config owns telegram login workflow', () => {
  assert.equal(componentSource.includes(`'/api/telegram/user'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/login/send-code'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/login/sign-in'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/login/password'`), true)
  assert.equal(componentSource.includes(`'/api/telegram/logout'`), true)
})
