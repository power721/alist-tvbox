<script setup lang="ts">
// @ts-nocheck
import {VueDraggable} from "vue-draggable-plus";
import {computed, onMounted, onUnmounted, ref, watch} from "vue";
import axios from "axios";
import {ElMessage} from "element-plus";
import Sortable from "sortablejs";
import {Check, Close} from "@element-plus/icons-vue";
import {isPluginDragEnabledForUserAgent} from "@/utils/pluginDragSupport.mjs";

interface Channel {
  id: number | null
  order: number
  username: string
  title: string
  enabled: boolean
  webAccess: boolean
  changed: boolean
  valid: boolean
  accessHash: number | null
  type: number
}

interface PrivateChannel {
  id: number
  account_id: number
  telegram_channel_id: number
  title: string
  username: string
  type: string
  last_message_id: number
  last_sync_time: string
  web_access: boolean
  web_access_checked_at: string
  enabled: boolean
  changed: boolean
}

interface TelegramUser {
  id: number
  username: string
  first_name: string
  last_name: string
  phone: string
}

const cover = ref('')
const tgChannels = ref('')
const tgWebChannels = ref('')
const tgSearch = ref('')
const panSouUrl = ref('')
const panSouSource = ref('all')
const panSouChannels = ref('custom')
const panSouAuthEnabled = ref(false)
const panSouUsername = ref('')
const panSouPassword = ref('')
const panSouProjectChannelsCount = ref(0)
const panSouBuiltinChannelsCount = ref(0)
const panSouPluginCount = ref(0)
const panSouPlugins = ref([])
const panSouLinkCheckEnabled = ref(false)
const panSouLinkCheckMaxCount = ref(30)
const plugins = ref([])
const tgSortField = ref('time')
const tgTimeout = ref(3000)
const channels = ref<Channel[]>([])
const privateChannels = ref<PrivateChannel[]>([])
const privateChannelKeyword = ref('')
const privateChannelVisibility = ref('all')
const privateChannelType = ref('all')
const privateChannelsChanged = ref(false)
const privateChannelsLoading = ref(false)
const activeRows = ref<Channel[]>([])
const activePrivateRows = ref<PrivateChannel[]>([])
const tgPhase = ref(1)
const tgPhone = ref('')
const tgCode = ref('')
const tgPassword = ref('')
const tgUser = ref<TelegramUser>({id: 0, username: '', first_name: '', last_name: '', phone: ''})
const defaultDriverOrder = '9,10,5,7,8,3,2,0,6,1,12,magnet,ed2k'.split(',')
const tgDrivers = ref([...defaultDriverOrder])
const tgDriverOrder = ref([...defaultDriverOrder])
const formVisible = ref(false)
const dialogTitle = ref('')
const form = ref<Channel>({
  id: 0,
  username: '',
  title: '',
  enabled: true,
  webAccess: false,
  changed: false,
  valid: true,
  order: 1,
  type: -1,
  accessHash: 0,
})

const options = [
  {label: '全部', value: 'ALL'},
  {label: '百度', value: '10'},
  {label: '天翼', value: '9'},
  {label: '夸克', value: '5'},
  {label: 'UC', value: '7'},
  {label: '阿里', value: '0'},
  {label: '115', value: '8'},
  {label: '123', value: '3'},
  {label: '迅雷', value: '2'},
  {label: '移动', value: '6'},
  {label: 'PikPak', value: '1'},
  {label: '光鸭', value: '12'},
  {label: '磁力', value: 'magnet'},
  {label: 'ED2K', value: 'ed2k'},
]

const options2 = [
  {label: '聚合', value: -1},
  {label: '夸克', value: 5},
  {label: 'UC', value: 7},
  {label: '阿里', value: 0},
  {label: '115', value: 8},
  {label: '123', value: 3},
  {label: '天翼', value: 9},
  {label: '百度', value: 10},
  {label: '迅雷', value: 2},
  {label: '移动', value: 6},
  {label: 'PikPak', value: 1},
  {label: '光鸭', value: 12},
]

const orders = [
  {label: '时间', value: 'time'},
  {label: '网盘', value: 'type'},
  {label: '名称', value: 'name'},
  {label: '频道', value: 'channel'},
]

const sources = [
  {label: '全部', value: 'all'},
  {label: '电报', value: 'tg'},
  {label: '插件', value: 'plugin'},
]

const panSouChannelLists = [
  {label: '自定义', value: 'custom'},
  {label: '项目内置', value: 'project'},
  {label: '盘搜内置', value: 'pansou'},
]

const privateChannelVisibilityOptions = [
  {label: '全部状态', value: 'all'},
  {label: '公开', value: 'public'},
  {label: '私密', value: 'private'},
]

const activeName = ref('basic')

const getTypeName = (id: number) => {
  return options2.find(e => e.value === id)?.label
}

const getPrivateChannelVisibilityName = (row: PrivateChannel) => {
  return row.username ? '公开' : '私密'
}

const formatTime = (t: string) => {
  if (!t) {
    return ""
  }
  const date = new Date(t);
  return date.getFullYear() + '-' +
    String(date.getMonth() + 1).padStart(2, '0') + '-' +
    String(date.getDate()).padStart(2, '0') + ' ' +
    String(date.getHours()).padStart(2, '0') + ':' +
    String(date.getMinutes()).padStart(2, '0') + ':' +
    String(date.getSeconds()).padStart(2, '0');
}

const getPrivateChannelVisibilityValue = (row: PrivateChannel) => {
  return row.username ? 'public' : 'private'
}

const privateChannelTypeOptions = computed(() => {
  const types = [...new Set(privateChannels.value.map(row => row.type).filter(type => type))]
    .sort()
  return [
    {label: '全部类型', value: 'all'},
    ...types.map(type => ({label: type, value: type})),
  ]
})

const filteredPrivateChannels = computed(() => {
  const keyword = privateChannelKeyword.value.trim().toLowerCase()
  return privateChannels.value.filter(row => {
    if (keyword) {
      const text = [row.title, row.username]
        .map(value => `${value || ''}`.toLowerCase())
        .join(' ')
      if (!text.includes(keyword)) {
        return false
      }
    }
    if (privateChannelVisibility.value !== 'all' && getPrivateChannelVisibilityValue(row) !== privateChannelVisibility.value) {
      return false
    }
    if (privateChannelType.value !== 'all' && row.type !== privateChannelType.value) {
      return false
    }
    return true
  })
})

const normalizeDriverOrder = (value: string) => {
  const ids = value.split(',').map(e => e.trim()).filter(e => e)
  defaultDriverOrder.forEach(e => {
    if (!ids.includes(e)) {
      ids.push(e)
    }
  })
  return ids.map(e => {
    return {
      id: e,
      name: options.find(o => o.value === e)?.label || e
    }
  })
}

const loadPanSouInfo = () => {
  return axios.get('/api/pansou').then(({data}) => {
    plugins.value = data.plugins || []
    panSouPluginCount.value = data.plugin_count || plugins.value.length
    panSouAuthEnabled.value = data.auth_enabled === true
    panSouProjectChannelsCount.value = data.project_channels_count || 0
    panSouBuiltinChannelsCount.value = data.channels_count || data.channels?.length || 0
  })
}

const getPanSouChannelCount = (value: string) => {
  if (value === 'custom') {
    return channels.value.filter(e => e.enabled && e.valid).length
  }
  if (value === 'project') {
    return panSouProjectChannelsCount.value
  }
  if (value === 'pansou') {
    return panSouBuiltinChannelsCount.value
  }
  return 0
}

const updateTgTimeout = () => {
  axios.post('/api/settings', {name: 'tg_timeout', value: tgTimeout.value + ''}).then(() => {
    ElMessage.success('更新成功')
  })
}

const updateTgSearch = () => {
  axios.post('/api/settings', {name: 'tg_search', value: tgSearch.value}).then(({data}) => {
    tgSearch.value = data.value
    ElMessage.success('更新成功')
  })
}

const updatePanSouUrl = () => {
  axios.post('/api/settings', {name: 'pan_sou_url', value: panSouUrl.value}).then(({data}) => {
    panSouUrl.value = data.value
    loadPanSouInfo()
    ElMessage.success('更新成功')
  })
}

const updatePanSouSource = () => {
  axios.post('/api/settings', {name: 'pan_sou_source', value: panSouSource.value}).then(() => {
    ElMessage.success('更新成功')
  })
}

const updatePanSouChannels = () => {
  axios.post('/api/settings', {name: 'pan_sou_channels', value: panSouChannels.value}).then(() => {
    ElMessage.success('更新成功')
  })
}

const updatePanSouAuth = () => {
  axios.post('/api/settings', {name: 'pan_sou_username', value: panSouUsername.value}).then()
  axios.post('/api/settings', {name: 'pan_sou_password', value: panSouPassword.value}).then(() => {
    ElMessage.success('更新成功')
  })
}

const updatePanSouLinkCheck = () => {
  axios.post('/api/settings', {name: 'pan_sou_link_check_enabled', value: panSouLinkCheckEnabled.value}).then()
  axios.post('/api/settings', {name: 'pan_sou_link_check_max_count', value: panSouLinkCheckMaxCount.value}).then(() => {
    ElMessage.success('更新成功')
  })
}

const updateCover = () => {
  axios.post('/api/settings', {name: 'video_cover', value: cover.value}).then(({data}) => {
    cover.value = data.value
    ElMessage.success('更新成功')
  })
}

const updateDrivers = () => {
  const order = tgDriverOrder.value.map(e => e.id).join(',')
  axios.post('/api/settings', {name: 'tgDriverOrder', value: order}).then()
  const value = tgDriverOrder.value.map(e => e.id).filter(e => tgDrivers.value.includes(e)).join(',')
  axios.post('/api/settings', {name: 'tg_drivers', value: value}).then(({data}) => {
    tgDrivers.value = data.value.split(',')
    ElMessage.success('更新成功')
  })
}

const updatePlugins = () => {
  const value = panSouPlugins.value.join(',')
  axios.post('/api/settings', {name: 'panSouPlugins', value: value}).then(({data}) => {
    panSouPlugins.value = data.value.split(',')
    ElMessage.success('更新成功')
  })
}

const updateOrder = () => {
  axios.post('/api/settings', {name: 'tg_sort_field', value: tgSortField.value}).then(() => {
    ElMessage.success('更新成功')
  })
}

const changed = ref(false)
const tableKey = ref(0)
const privateTableKey = ref(0)
const channelDragEnabled = isPluginDragEnabledForUserAgent(window.navigator.userAgent)
let channelSortable: Sortable | null = null
let privateChannelSortable: Sortable | null = null

const privateChannelDragEnabled = computed(() => {
  return channelDragEnabled && !privateChannelKeyword.value.trim()
    && privateChannelVisibility.value === 'all'
    && privateChannelType.value === 'all'
})

const tableRowClassName = ({row}: {
  row: Channel | PrivateChannel
  rowIndex: number
}) => {
  if (row.changed) {
    return 'warning-row'
  }
  return ''
}

const markPrivateChannelChanged = (row: PrivateChannel) => {
  row.changed = true
  privateChannelsChanged.value = true
}

const treeToTile = <T>(treeData: T[]) => {
  return [...treeData]
}

const rowDrop = () => {
  if (!channelDragEnabled) {
    channelSortable?.destroy()
    channelSortable = null
    return
  }
  const tbody = document.querySelector("#channels tbody") as HTMLElement;
  channelSortable?.destroy()
  channelSortable = Sortable.create(tbody, {
    animation: 500,
    handle: ".el-table__row",
    draggable: ".el-table__row",
    onMove() {
      activeRows.value = treeToTile(channels.value)
      return true
    },
    onEnd: (event: any) => {
      let oldIndex = event.oldIndex
      let newIndex = event.newIndex
      const oldRow = activeRows.value[oldIndex]
      const newRow = activeRows.value[newIndex]
      if (!oldRow || oldIndex === newIndex || oldRow.id === newRow.id) {
        return
      }

      activeRows.value.splice(oldIndex, 1)
      activeRows.value.splice(newIndex, 0, oldRow)
      let order = 0
      const items: Channel[] = []

      activeRows.value.forEach(e => {
        e.order = order++
        items.push(e)
      })
      channels.value = items

      tableKey.value = Math.random()
      setTimeout(() => rowDrop(), 500)

      oldRow.changed = true
      newRow.changed = true
      changed.value = true
    },
  });
}

const privateRowDrop = () => {
  if (!privateChannelDragEnabled.value) {
    privateChannelSortable?.destroy()
    privateChannelSortable = null
    return
  }
  const tbody = document.querySelector("#private-channels tbody") as HTMLElement | null
  if (!tbody) {
    return
  }
  privateChannelSortable?.destroy()
  privateChannelSortable = Sortable.create(tbody, {
    animation: 500,
    handle: ".el-table__row",
    draggable: ".el-table__row",
    onMove() {
      activePrivateRows.value = treeToTile(filteredPrivateChannels.value)
      return true
    },
    onEnd: (event: any) => {
      let oldIndex = event.oldIndex
      let newIndex = event.newIndex
      const oldRow = activePrivateRows.value[oldIndex]
      const newRow = activePrivateRows.value[newIndex]
      if (!oldRow || oldIndex === newIndex || oldRow.id === newRow.id) {
        return
      }

      activePrivateRows.value.splice(oldIndex, 1)
      activePrivateRows.value.splice(newIndex, 0, oldRow)
      privateChannels.value = activePrivateRows.value

      privateTableKey.value = Math.random()
      setTimeout(() => privateRowDrop(), 500)

      oldRow.changed = true
      newRow.changed = true
      privateChannelsChanged.value = true
    },
  })
}

const handleAdd = () => {
  dialogTitle.value = '添加频道'
  form.value = {
    id: null,
    username: '',
    title: '',
    enabled: true,
    webAccess: false,
    changed: false,
    valid: true,
    order: 1,
    type: -1,
    accessHash: null,
  }
  formVisible.value = true
}

const handleEdit = (data: Channel) => {
  dialogTitle.value = '更新频道 - ' + data.username
  form.value = Object.assign({}, data)
  formVisible.value = true
}

const deleteChannel = (id: number) => {
  axios.delete('/api/telegram/channels/' + id).then(() => {
    loadChannels()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  if (!form.value.username) {
    ElMessage.error('无效的用户名')
    return
  }
  let url = '/api/telegram/resolveUsername'
  if (form.value.id) {
    url = '/api/telegram/channels'
  }
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    loadChannels()
  })
}

const handleSave = () => {
  axios.put('/api/telegram/channels', channels.value).then(({data}) => {
    ElMessage.success('保存成功')
    changed.value = false
    channels.value = data
  })
}

const reload = () => {
  axios.post('/api/telegram/reloadChannels').then(({data}) => {
    ElMessage.success('重置成功')
    changed.value = false
    channels.value = data
  })
}

const validate = () => {
  axios.post('/api/telegram/validateChannels').then(({data}) => {
    ElMessage.success('校验完成')
    changed.value = false
    channels.value = data
  })
}

const loadChannels = () => {
  return axios.get('/api/telegram/channels').then(({data}) => {
    channels.value = data
    changed.value = false
    return data
  })
}

const loadPrivateChannels = () => {
  privateChannelsLoading.value = true
  return axios.get('/api/telegram/private/channels').then(({data}) => {
    privateChannels.value = data || []
    privateChannelsChanged.value = false
    setTimeout(() => privateRowDrop(), 500)
  }).finally(() => {
    privateChannelsLoading.value = false
  })
}

const privateChannelIds = () => {
  return privateChannels.value.filter(e => e.enabled).map(e => e.id)
}

const savePrivateChannels = () => {
  axios.put('/api/telegram/private/channels', {channel_ids: privateChannelIds()}).then(({data}) => {
    privateChannels.value = data || []
    privateChannelsChanged.value = false
    setTimeout(() => privateRowDrop(), 500)
    ElMessage.success('保存成功')
  })
}

const syncPrivateChannelList = () => {
  privateChannelsLoading.value = true
  axios.post('/api/telegram/private/channels/sync-list').then(({data}) => {
    const queued = (data || []).filter((e: any) => e.status === 'queued').length
    ElMessage.success(queued ? `已提交 ${queued} 个账号的频道列表同步` : '频道列表同步完成')
    return loadPrivateChannels()
  }).finally(() => {
    privateChannelsLoading.value = false
  })
}

const syncPrivateChannel = (row: PrivateChannel) => {
  axios.post(`/api/telegram/private/channels/${row.id}/sync`).then(({data}) => {
    if (data?.status) {
      ElMessage.success(`已提交频道同步任务：${data.status}`)
      return
    }
    ElMessage.success(`已同步 ${data?.messages || 0} 条消息，发现 ${data?.links || 0} 个链接`)
  })
}

const updatePrivateChannelWebAccess = (channel: PrivateChannel) => {
  const row = privateChannels.value.find(e => e.id === channel.id)
  if (!row) {
    privateChannels.value.push(channel)
    return
  }
  row.web_access = channel.web_access
  row.web_access_checked_at = channel.web_access_checked_at
}

const checkPrivateChannelWebAccess = (row: PrivateChannel) => {
  axios.post(`/api/telegram/private/channels/${row.id}/web-access/check`).then(({data}) => {
    if (data) {
      updatePrivateChannelWebAccess(data)
    }
    ElMessage.success('检测完成')
  })
}

const checkPrivateChannelsWebAccess = () => {
  privateChannelsLoading.value = true
  axios.post('/api/telegram/private/channels/web-access/check').then(({data}) => {
    const channels = data || []
    channels.forEach(updatePrivateChannelWebAccess)
    ElMessage.success('检测完成')
  }).finally(() => {
    privateChannelsLoading.value = false
  })
}

const emptyTelegramUser = (): TelegramUser => ({id: 0, username: '', first_name: '', last_name: '', phone: ''})

const loadTelegramUser = () => {
  return axios.get('/api/telegram/user').then(({data}) => {
    tgUser.value = data || emptyTelegramUser()
    tgPhase.value = tgUser.value.id ? 0 : 1
  })
}

const logoutTelegram = () => {
  axios.post('/api/telegram/logout').then(() => {
    ElMessage.success('退出登录成功')
    tgUser.value = emptyTelegramUser()
    tgPhase.value = 1
  })
}

const sendTgPhone = () => {
  if (!tgPhone.value) {
    return
  }
  axios.post('/api/telegram/login/send-code', {phone: tgPhone.value}).then(() => {
    tgPhase.value = 3
    ElMessage.success('验证码已发送')
  }, () => {
    ElMessage.error('发送验证码失败')
  })
}

const sendTgCode = () => {
  if (!tgCode.value) {
    return
  }
  axios.post('/api/telegram/login/sign-in', {phone: tgPhone.value, code: tgCode.value}).then(({data}) => {
    if (data && data.password_required) {
      tgPhase.value = 5
      return
    }
    loadTelegramUser()
    loadPrivateChannels()
  }, () => {
    ElMessage.error('登录失败')
  })
}

const sendTgPassword = () => {
  if (!tgPassword.value) {
    return
  }
  axios.post('/api/telegram/login/password', {phone: tgPhone.value, password: tgPassword.value}).then(() => {
    loadTelegramUser()
    loadPrivateChannels()
  }, () => {
    ElMessage.error('密码验证失败')
  })
}

watch([privateChannelKeyword, privateChannelVisibility, privateChannelType], () => {
  setTimeout(() => privateRowDrop(), 0)
})

onMounted(() => {
  loadChannels().then(() => {
    rowDrop()
  })
  loadPrivateChannels()
  loadTelegramUser()
  axios.get('/api/settings').then(({data}) => {
    tgChannels.value = data.tg_channels
    tgWebChannels.value = data.tg_web_channels
    tgSearch.value = data.tg_search
    panSouUrl.value = data.pan_sou_url
    if (panSouUrl.value) {
      loadPanSouInfo()
    }
    panSouUsername.value = data.pan_sou_username
    panSouPassword.value = data.pan_sou_password
    if (data.panSouPlugins && data.panSouPlugins.length) {
      panSouPlugins.value = data.panSouPlugins.split(',')
    }
    panSouSource.value = data.pan_sou_source || 'all'
    panSouChannels.value = data.pan_sou_channels || 'custom'
    panSouLinkCheckEnabled.value = data.pan_sou_link_check_enabled === 'true'
    panSouLinkCheckMaxCount.value = +(data.pan_sou_link_check_max_count || 30)
    tgSortField.value = data.tg_sort_field || 'time'
    tgDriverOrder.value = normalizeDriverOrder(data.tgDriverOrder || '')
    if (data.tg_drivers && data.tg_drivers.length) {
      tgDrivers.value = data.tg_drivers.split(',')
    }
    cover.value = data.video_cover
    tgTimeout.value = +data.tg_timeout
  })
})

onUnmounted(() => {
  channelSortable?.destroy()
  privateChannelSortable?.destroy()
})
</script>

<template>
  <el-tabs v-model="activeName" class="demo-tabs">
    <el-tab-pane label="基本配置" name="basic">
      <el-form label-width="140">
        <el-form-item label="搜索超时时间">
          <el-input-number v-model="tgTimeout" :min="500" :max="30000"/>&nbsp;毫秒
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updateTgTimeout">更新</el-button>
        </el-form-item>
        <el-form-item label="PanSou地址">
          <el-input v-model="panSouUrl" placeholder="http://IP:8888"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updatePanSouUrl">更新</el-button>
          <a class="hint" target="_blank" title="部署纯后端" href="https://github.com/fish2018/pansou">部署</a>
          <a class="hint" target="_blank" title="部署前端后端" href="https://github.com/fish2018/pansou-web">部署</a>
        </el-form-item>
        <el-form-item label="PanSou用户名" v-if="panSouUrl && panSouAuthEnabled">
          <el-input v-model="panSouUsername"/>
        </el-form-item>
        <el-form-item label="PanSou密码" v-if="panSouUrl && panSouAuthEnabled">
          <el-input v-model="panSouPassword" type="password" show-password/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updatePanSouAuth" v-if="panSouUrl && panSouAuthEnabled">更新</el-button>
        </el-form-item>
        <el-form-item label="PanSou数据源" v-if="panSouUrl">
          <el-radio-group v-model="panSouSource" class="ml-4">
            <el-radio size="large" v-for="item in sources" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updatePanSouSource" v-if="panSouUrl">更新</el-button>
        </el-form-item>
        <el-form-item label="PanSou频道列表" v-if="panSouUrl">
          <el-radio-group v-model="panSouChannels" class="ml-4">
            <el-radio size="large" v-for="item in panSouChannelLists" :key="item.value" :value="item.value">
              {{ item.label }}({{ getPanSouChannelCount(item.value) }})
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updatePanSouChannels" v-if="panSouUrl">更新</el-button>
        </el-form-item>
        <el-form-item label="PanSou插件" v-if="panSouUrl">
          <el-checkbox-group v-model="panSouPlugins">
            <el-checkbox v-for="item in plugins" :label="item" :value="item" :key="item"/>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-if="panSouUrl">
          <el-button type="primary" @click="updatePlugins">更新</el-button>
          <span class="hint">已启用插件 {{ panSouPluginCount }} 个</span>
          <span class="hint">留空使用全部插件搜索</span>
        </el-form-item>
        <el-form-item label="链接检测" v-if="panSouUrl">
          <el-switch v-model="panSouLinkCheckEnabled"/>
          <span class="hint">自动检查盘搜搜索结果的有效性</span>
        </el-form-item>
        <el-form-item label="检测数量上限" v-if="panSouUrl">
          <el-input-number v-model="panSouLinkCheckMaxCount" :min="0" :max="500"/>
          <span class="hint">仅当网盘结果数量小于等于该值时检查，磁力和ED2K不计算数量</span>
        </el-form-item>
        <el-form-item v-if="panSouUrl">
          <el-button type="primary" @click="updatePanSouLinkCheck">更新</el-button>
        </el-form-item>
        <el-form-item label="网盘顺序">
          <el-checkbox-group v-model="tgDrivers">
            <VueDraggable ref="el" v-model="tgDriverOrder">
              <el-checkbox v-for="item in tgDriverOrder" :label="item.name" :value="item.id" :key="item.id">
              </el-checkbox>
            </VueDraggable>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updateDrivers">更新</el-button>
          <span class="hint">拖动网盘设置顺序</span>
        </el-form-item>
        <el-form-item label="排序字段">
          <el-radio-group v-model="tgSortField" class="ml-4">
            <el-radio size="large" v-for="item in orders" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updateOrder">更新</el-button>
        </el-form-item>
        <el-form-item label="默认视频壁纸">
          <el-input v-model="cover"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updateCover">更新</el-button>
        </el-form-item>
      </el-form>
    </el-tab-pane>
    <el-tab-pane label="公开频道" name="public-channels">
      <el-row justify="end">
        <span style="margin-right: 16px" v-if="channelDragEnabled">可以拖动行排序</span>
        <el-button @click="loadChannels">刷新</el-button>
        <el-popconfirm @confirm="reload" title="是否从配置文件加载全部频道？">
          <template #reference>
            <el-button type="danger">重置</el-button>
          </template>
        </el-popconfirm>
        <el-button @click="validate">校验</el-button>
        <el-button type="primary" :disabled="!changed" @click="handleSave">保存</el-button>
        <el-button type="primary" @click="handleAdd">添加</el-button>
      </el-row>
      <div class="space"></div>
      <el-table :data="channels"
                :row-class-name="tableRowClassName"
                row-key="id"
                id="channels"
                :key="tableKey"
                style="width: 100%">
        <el-table-column prop="order" label="顺序" width="60">
          <template #default="scope">
            <span :class="channelDragEnabled ? 'pointer' : 'order-text'">{{ scope.row.order }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="120"/>
        <el-table-column prop="username" label="用户名" width="160">
          <template #default="scope">
            <a :href="'https://t.me/'+scope.row.username" target="_blank">
              {{ scope.row.username }}
            </a>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题"/>
        <el-table-column prop="web" label="网页访问" width="100">
          <template #default="scope">
            <a :href="'https://t.me/s/'+scope.row.username" target="_blank" v-if="scope.row.webAccess">
              web
            </a>
            <span v-else></span>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="90">
          <template #default="scope">
            {{ getTypeName(scope.row.type) }}
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="开启搜索？" width="140">
          <template #default="scope">
            <el-switch v-model="scope.row.enabled" @change="changed=true"/>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="70">
          <template #default="scope">
            <el-icon v-if="scope.row.valid">
              <Check/>
            </el-icon>
            <el-icon v-else>
              <Close/>
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="140">
          <template #default="scope">
            <div>
              <el-button link type="primary" @click="handleEdit(scope.row)">编辑</el-button>
              <el-popconfirm @confirm="deleteChannel(scope.row.id)" title="是否删除频道？">
                <template #reference>
                  <el-button link type="danger">删除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-tab-pane>
    <el-tab-pane label="我的频道" name="private-channels">
      <el-row class="private-channel-toolbar" justify="end">
        <span style="margin-right: 16px" v-if="privateChannelDragEnabled">可以拖动行排序</span>
        <el-input class="private-channel-filter-keyword" v-model="privateChannelKeyword" clearable
                  placeholder="搜索频道"/>
        <el-select class="private-channel-filter-select" v-model="privateChannelVisibility" placeholder="公开状态">
          <el-option v-for="item in privateChannelVisibilityOptions"
                     :key="item.value"
                     :label="item.label"
                     :value="item.value"/>
        </el-select>
        <el-select class="private-channel-filter-select" v-model="privateChannelType" placeholder="类型">
          <el-option v-for="item in privateChannelTypeOptions"
                     :key="item.value"
                     :label="item.label"
                     :value="item.value"/>
        </el-select>
        <el-button @click="syncPrivateChannelList" :loading="privateChannelsLoading">同步频道列表</el-button>
        <el-button @click="loadPrivateChannels">刷新</el-button>
        <el-button @click="checkPrivateChannelsWebAccess" :loading="privateChannelsLoading">批量检测</el-button>
        <el-button type="primary" :disabled="!privateChannelsChanged" @click="savePrivateChannels">保存</el-button>
      </el-row>
      <div class="space"></div>
      <el-table :data="filteredPrivateChannels"
                v-loading="privateChannelsLoading"
                :row-class-name="tableRowClassName"
                row-key="id"
                id="private-channels"
                :key="privateTableKey"
                style="width: 100%">
        <el-table-column label="顺序" width="60">
          <template #default="scope">
            <span :class="privateChannelDragEnabled ? 'pointer' : 'order-text'">{{ scope.$index + 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="90" sortable/>
        <el-table-column prop="account_id" label="账号" width="90" sortable/>
        <el-table-column prop="title" label="标题" sortable/>
        <el-table-column prop="username" label="用户名" width="180" sortable>
          <template #default="scope">
            <a :href="'https://t.me/'+scope.row.username" target="_blank" v-if="scope.row.username">
              {{ scope.row.username }}
            </a>
          </template>
        </el-table-column>
        <el-table-column label="公开状态" width="100" sortable>
          <template #default="scope">
            {{ getPrivateChannelVisibilityName(scope.row) }}
          </template>
        </el-table-column>
        <el-table-column prop="web_access" label="网页访问" width="100" sortable>
          <template #default="scope">
            <a :href="'https://t.me/s/'+scope.row.username" target="_blank" v-if="scope.row.web_access && scope.row.username">
              web
            </a>
            <span v-else></span>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="120" sortable/>
        <el-table-column prop="last_message_id" label="最新消息" width="120" sortable/>
        <el-table-column prop="last_sync_time" label="同步时间" width="210" sortable>
          <template #default="scope">
            {{ formatTime(scope.row.last_sync_time) }}
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="参与搜索" width="120" sortable>
          <template #default="scope">
            <el-switch v-model="scope.row.enabled" @change="markPrivateChannelChanged(scope.row)"/>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="140">
          <template #default="scope">
            <el-button link type="primary" @click="syncPrivateChannel(scope.row)">同步</el-button>
            <el-button link type="primary" :disabled="!scope.row.username" @click="checkPrivateChannelWebAccess(scope.row)">检测</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-tab-pane>
    <el-tab-pane label="电报管理" name="telegram">
      <el-form label-width="120">
        <template v-if="tgUser.id">
          <el-form-item label="用户ID">{{ tgUser.id }}</el-form-item>
          <el-form-item label="用户名">{{ tgUser.username }}</el-form-item>
          <el-form-item label="姓名">{{ tgUser.first_name }} {{ tgUser.last_name }}</el-form-item>
          <el-form-item label="电话">{{ tgUser.phone }}</el-form-item>
          <el-form-item>
            <el-button type="danger" @click="logoutTelegram">退出登录</el-button>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="电话号码" required v-if="tgPhase === 1">
            <el-input style="width: 260px" v-model="tgPhone" autocomplete="off" placeholder="+8612345678901"/>
            <el-button @click="sendTgPhone">发送验证码</el-button>
          </el-form-item>
          <el-form-item label="验证码" required v-if="tgPhase === 3">
            <el-input style="width: 160px" v-model="tgCode" autocomplete="off"/>
            <el-button @click="sendTgCode">登录</el-button>
          </el-form-item>
          <el-form-item label="密码" required v-if="tgPhase === 5">
            <el-input style="width: 260px" v-model="tgPassword" type="password" show-password autocomplete="off"/>
            <el-button @click="sendTgPassword">确认</el-button>
          </el-form-item>
        </template>
      </el-form>
    </el-tab-pane>
  </el-tabs>

  <el-dialog v-model="formVisible" :title="dialogTitle">
    <el-form label-width="140" :model="form">
      <el-form-item label="用户名" required>
        <el-input style="width: 200px" v-model="form.username" autocomplete="off"/>
      </el-form-item>
      <!--      <el-form-item label="ID">-->
      <!--        <el-input v-model="form.id" autocomplete="off"/>-->
      <!--      </el-form-item>-->
      <!--      <el-form-item label="Access Hash">-->
      <!--        <el-input v-model="form.accessHash" autocomplete="off"/>-->
      <!--      </el-form-item>-->
      <el-form-item label="开启搜索？">
        <el-switch v-model="form.enabled"/>
      </el-form-item>
      <el-form-item label="资源类型">
        <el-select style="width: 120px" v-model="form.type">
          <el-option
            v-for="item in options2"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="顺序">
        <el-input-number v-model="form.order" :min="0"/>
      </el-form-item>
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ form.id ? '更新' : '添加' }}</el-button>
      </span>
    </template>
  </el-dialog>

</template>

<style scoped>
::v-deep .el-table .cell {
  text-align: center;
}

::v-deep .sortable-chosen > td {
  background-color: #eff2f6 !important;
  color: #409eff;
}

::v-deep .el-table--enable-row-hover .el-table__body tr:hover > td {
  background-color: #fff;
}

.flex {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.pointer {
  cursor: pointer;
}

.order-text {
  cursor: default;
}

.private-channel-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.private-channel-filter-keyword {
  width: 260px;
  max-width: 100%;
}

.private-channel-filter-select {
  width: 140px;
}

.private-channel-toolbar .el-button + .el-button {
  margin-left: 0;
}

@media (max-width: 640px) {
  .private-channel-filter-keyword,
  .private-channel-filter-select {
    width: 100%;
  }
}
</style>
