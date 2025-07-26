<script setup lang="ts">
// @ts-nocheck
import {VueDraggable} from "vue-draggable-plus";
import {onMounted, ref} from "vue";
import axios from "axios";
import {ElMessage} from "element-plus";
import Sortable from "sortablejs";
import {Check, Close} from "@element-plus/icons-vue";

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

const cover = ref('')
const tgChannels = ref('')
const tgWebChannels = ref('')
const tgSearch = ref('')
const panSouUrl = ref('')
const panSouSource = ref('all')
const tgSortField = ref('time')
const tgTimeout = ref(3000)
const channels = ref<Channel[]>([])
const activeRows = ref<Channel[]>([])
const tgDrivers = ref('9,10,5,7,8,3,2,0,6,1'.split(','))
const tgDriverOrder = ref('9,10,5,7,8,3,2,0,6,1'.split(','))
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

const activeName = ref('basic')

const getTypeName = (id: number) => {
  return options2.find(e => e.value === id)?.label
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
    ElMessage.success('更新成功')
  })
}

const updatePanSouSource = () => {
  axios.post('/api/settings', {name: 'pan_sou_source', value: panSouSource.value}).then(() => {
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

const updateOrder = () => {
  axios.post('/api/settings', {name: 'tg_sort_field', value: tgSortField.value}).then(() => {
    ElMessage.success('更新成功')
  })
}

const changed = ref(false)
const tableKey = ref(0)

const tableRowClassName = ({row}: {
  row: Channel
  rowIndex: number
}) => {
  if (row.changed) {
    return 'warning-row'
  }
  return ''
}

const treeToTile = (treeData: Channel[]) => {
  return [...treeData]
}

const rowDrop = () => {
  const tbody = document.querySelector("#channels tbody") as HTMLElement;
  Sortable.create(tbody, {
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

onMounted(() => {
  loadChannels().then(() => {
    rowDrop()
  })
  axios.get('/api/settings').then(({data}) => {
    tgChannels.value = data.tg_channels
    tgWebChannels.value = data.tg_web_channels
    tgSearch.value = data.tg_search
    panSouUrl.value = data.pan_sou_url
    panSouSource.value = data.pan_sou_source || 'all'
    tgSortField.value = data.tg_sort_field || 'time'
    tgDriverOrder.value = data.tgDriverOrder.split(',').map(e => {
      return {
        id: e,
        name: options.find(o => o.value === e)?.label
      }
    })
    if (data.tg_drivers && data.tg_drivers.length) {
      tgDrivers.value = data.tg_drivers.split(',')
    }
    cover.value = data.video_cover
    tgTimeout.value = +data.tg_timeout
  })
})
</script>

<template>
  <el-tabs v-model="activeName" class="demo-tabs">
    <el-tab-pane label="基本配置" name="basic">
      <el-form label-width="140">
        <el-form-item label="远程搜索地址">
          <el-input v-model="tgSearch" placeholder="http://IP:7856"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updateTgSearch">更新</el-button>
          <a class="hint" target="_blank" href="https://t.me/alist_tvbox/711">部署</a>
        </el-form-item>
        <el-form-item label="PanSou地址">
          <el-input v-model="panSouUrl" placeholder="http://IP:8888"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updatePanSouUrl">更新</el-button>
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
        <el-form-item label="搜索超时时间">
          <el-input-number v-model="tgTimeout" :min="500" :max="30000"/>&nbsp;毫秒
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updateTgTimeout">更新</el-button>
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
    <el-tab-pane label="频道管理" name="second">
      <el-row justify="end">
        <span style="margin-right: 16px">可以拖动行排序</span>
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
            <span class="pointer">{{ scope.row.order }}</span>
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
</style>
