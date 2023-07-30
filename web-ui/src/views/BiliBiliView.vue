<template>
  <div class="sites">
    <div class="flex">
      <div v-if="userInfo">
        <span v-if="userInfo.uname">用户名：{{ userInfo.uname }}</span>
        <span class="hint">登录状态：{{ userInfo.isLogin ? '已登录' : '未登录' }}</span>
        <span v-if="userInfo.uname" class="hint">会员状态：{{
            userInfo.vipType ? userInfo.vip_label.text : '无会员'
          }}</span>
      </div>
      <div class="">
        <el-button type="primary" @click="scanLogin">登录</el-button>
        <el-button type="primary" @click="settingVisible=true">配置</el-button>
      </div>
    </div>

    <h1>分类列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" :disabled="!changed" @click="handleSave">保存</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="list"
              :row-class-name="tableRowClassName"
              row-key="id"
              :key="tableKey"
              style="width: 100%">
      <el-table-column prop="order" label="顺序" sortable width="100">
        <template #default="scope">
          <span class="pointer">{{scope.row.order}}</span>
        </template>
      </el-table-column>
      <el-table-column prop="id" label="ID" sortable width="100"/>
      <el-table-column prop="name" label="名称" width="200"/>
      <el-table-column prop="value" label="值" sortable/>
      <el-table-column prop="type" label="类型" sortable width="180">
        <template #default="scope">
          <span v-if="scope.row.type==1">一级分类</span>
          <span v-if="scope.row.type==2">二级分类</span>
          <span v-if="scope.row.type==3">频道</span>
          <span v-if="scope.row.type==4">搜索</span>
          <span v-if="scope.row.type==5">UP主</span>
        </template>
      </el-table-column>
      <el-table-column prop="parentId" label="父类ID" sortable width="180"/>
      <el-table-column prop="show" label="显示？" sortable width="140">
        <template #default="scope">
          <el-switch v-model="scope.row.show" @change="changed=true"/>
        </template>
      </el-table-column>
      <el-table-column prop="reserved" label="保留的？" sortable width="140">
        <template #default="scope">
          <el-icon v-if="scope.row.reserved">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <div v-if="!scope.row.reserved">
            <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="名称" label-width="140">
          <el-input v-model="form.name" placeholder="显示的名称" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="值" label-width="140">
          <el-input v-model="form.value" placeholder="频道ID、UP主ID或者搜索关键词" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="显示？" label-width="140">
          <el-switch v-model="form.show"/>
        </el-form-item>
        <!--        <el-form-item label="保留的？" label-width="140">-->
        <!--          <el-switch v-model="form.reserved"/>-->
        <!--        </el-form-item>-->
        <el-form-item label="类型" label-width="140">
          <el-radio-group v-model="form.type" class="ml-4">
            <!--            <el-radio :label="1" size="large">一级分类</el-radio>-->
            <!--            <el-radio :label="2" size="large">二级分类</el-radio>-->
            <el-radio :label="3" size="large">频道</el-radio>
            <el-radio :label="4" size="large">搜索</el-radio>
                        <el-radio :label="5" size="large">UP主</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="顺序" label-width="140">
          <el-input-number v-model="form.order" :min="0"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除分类" width="30%">
      <p>是否删除分类 - {{ form.name }}</p>
      <p>{{ form.value }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSite">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="loginVisible" title="扫码登录" width="550px" @close="cancel">
      <div v-if="base64QrCode">
        <img :src="'data:image/png;base64,'+ base64QrCode" style="width: 500px;">
        <span class="hint">打开BiliBili手机客户端扫码</span>
      </div>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="loginVisible=false">取消</el-button>
        <!--        <el-button type="primary" @click="checkLogin">我已扫码</el-button>-->
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="settingVisible" title="B站设置" width="40%">
      <el-form label-width="150px">
        <el-form-item label="登录Cookie" label-width="120">
          <el-input v-model="bilibiliCookie" type="textarea" :rows="5"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updateBilibiliCookie">更新</el-button>
        </el-form-item>
        <el-form-item label="上报播放记录">
          <el-switch
            v-model="heartbeat"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
            @change="updateHeartbeat"
          />
        </el-form-item>
        <el-form-item label="可搜索">
          <el-switch
            v-model="searchable"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
            @change="updateSearchable"
          />
        </el-form-item>
        <el-form-item label="强制dash视频格式">
          <el-switch
            v-model="dash"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
            @change="updateDash"
          />
        </el-form-item>
        <el-form-item label="视频格式">
          <el-checkbox v-model="checks[0]" label="HDR" size="large"/>
          <el-checkbox v-model="checks[1]" label="4K" size="large"/>
          <el-checkbox v-model="checks[2]" label="杜比音频" size="large"/>
          <el-checkbox v-model="checks[3]" label="杜比视界" size="large"/>
          <el-checkbox v-model="checks[4]" label="8K" size="large"/>
          <el-checkbox v-model="checks[5]" label="AV1编码" size="large"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updateFnval">更新</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="settingVisible = false">取消</el-button>
      </span>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import Sortable from "sortablejs"
import {Check, Close} from '@element-plus/icons-vue'
import axios from "axios"
import {ElMessage} from "element-plus";

const columns = [
  {title: "ID", key: "id", dataKey: "id"},
  {title: "姓名", key: "name", dataKey: "name"},
  {title: "值", key: "value", dataKey: "value"},
  {title: "类型", key: "type", dataKey: "type"},
  {title: "显示", key: "show", dataKey: "show"},
  {title: "保留的", key: "reserved", dataKey: "reserved"},
  {title: "顺序", key: "order", dataKey: "order"},
  {title: "父类ID", key: "parentId", dataKey: "parentId"},
]

interface Nav {
  id: number
  order: number
  name: string
  value: string
  show: boolean
  reserved: boolean
  changed: boolean
  expanded: boolean
  type: number
  parentId: number
  children: Nav[]
}

const tableRowClassName = ({row}: {
  row: Nav
  rowIndex: number
}) => {
  if (row.changed) {
    return 'warning-row'
  }
  return ''
}
const base64QrCode = ref('')
const qrcodeKey = ref('')
const bilibiliCookie = ref('')
const checks = ref<boolean[]>([true, true, true, false, true, true])
const userInfo = ref<any>({})
const heartbeat = ref(false)
const searchable = ref(false)
const dash = ref(false)
const updateAction = ref(false)
const dialogTitle = ref('')
const list = ref<Nav[]>([])
const activeRows = ref<Nav[]>([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const settingVisible = ref(false)
const loginVisible = ref(false)
const changed = ref(false)
const tableKey = ref(0)
const form = ref<Nav>({
  id: 0,
  name: '',
  value: '',
  type: 3,
  show: true,
  reserved: false,
  changed: false,
  expanded: false,
  order: 1,
  parentId: 0,
  children: []
})

const treeToTile = (treeData: Nav[]) => {
  const arr: Nav[] = []
  const expanded = (data: Nav[]) => {
    if (data && data.length > 0) {
      data.filter(d => d).forEach(e => {
        arr.push(e)
        expanded(e['children'] || [])
      })
    }
  }
  expanded(treeData)
  return arr
}

const rowDrop = () => {
  const tbody = document.querySelector(".el-table__body-wrapper tbody") as HTMLElement;
  Sortable.create(tbody, {
    animation: 500,
    handle: ".el-table__row",
    draggable: ".el-table__row",
    onMove({dragged, related}) {
      activeRows.value = treeToTile(list.value)
      const oldRow = activeRows.value[(dragged as HTMLTableRowElement).rowIndex]
      const newRow = activeRows.value[(related as HTMLTableRowElement).rowIndex]
      if ((oldRow.type == 2) !== (newRow.type == 2)) {
        return false
      }
      return !(oldRow.type === 2 && oldRow.parentId !== newRow.parentId);
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
      let order = 1
      const items: Nav[] = []

      if (oldRow.type === 2) {
        const parent = list.value.find(e => e.id === oldRow.parentId)
        console.log(oldRow.id, oldRow.parentId, parent)
        activeRows.value.forEach(e => {
          if (e.type == 2 && e.parentId === oldRow.parentId) {
            e.order = order++
            items.push(e)
          }
        })
        if (parent) {
          parent.children = items
        }
      } else {
        activeRows.value.forEach(e => {
          if (e.type != 2) {
            e.order = order++
            items.push(e)
          }
        })
        list.value = items

        tableKey.value = Math.random()
        setTimeout(() => rowDrop(), 500)
      }

      oldRow.changed = true
      newRow.changed = true
      changed.value = true
    },
  });
}

const handleAdd = () => {
  dialogTitle.value = '添加分类'
  updateAction.value = false
  form.value = {
    id: 0,
    name: '',
    value: '',
    type: 3,
    show: true,
    reserved: false,
    changed: false,
    expanded: false,
    order: 1,
    parentId: 0,
    children: []
  }
  formVisible.value = true
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新分类 - ' + data.name
  updateAction.value = true
  form.value = Object.assign({}, data)
  formVisible.value = true
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSite = () => {
  dialogVisible.value = false
  axios.delete('/nav/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/nav/' + form.value.id : '/nav'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const handleSave = () => {
  const items = list.value.map(e => ({id: e.id, order: e.order, show: e.show, children: e.children}));
  axios.put('/nav', {list: items}).then(() => {
    ElMessage.success('保存成功')
    load()
  })
}

const updateBilibiliCookie = () => {
  axios.post('/settings', {name: 'bilibili_cookie', value: bilibiliCookie.value}).then(() => {
    ElMessage.success('更新成功')
    loadUser()
  })
}

const updateHeartbeat = () => {
  axios.post('/settings', {name: 'bilibili_heartbeat', value: heartbeat.value + ''}).then(() => {
    ElMessage.success('更新成功')
    loadUser()
  })
}

const updateSearchable = () => {
  axios.post('/settings', {name: 'bilibili_searchable', value: searchable.value + ''}).then(() => {
    ElMessage.success('更新成功')
  })
}

const updateDash = () => {
  axios.post('/settings', {name: 'bilibili_dash', value: dash.value + ''}).then(() => {
    ElMessage.success('更新成功')
  })
}

const updateFnval = () => {
  let val = 16
  let num = 64
  for (let flag of checks.value) {
    if (flag) {
      val += num
    }
    num *= 2
  }
  axios.post('/settings', {name: 'bilibili_fnval', value: val + ''}).then(() => {
    ElMessage.success('更新成功')
  })
}

const scanLogin = () => {
  base64QrCode.value = ''
  qrcodeKey.value = ''
  clearInterval(timer)
  axios.post('/bilibili/login', null).then(({data}) => {
    base64QrCode.value = data.image
    qrcodeKey.value = data.qrcode_key
    loginVisible.value = true
    checkLogin()
  })
}

let timer = 0
let count = 90

const checkLogin = () => {
  count = 180
  timer = setInterval(check, 1000)
}

const check = () => {
  if (count-- > 0) {
    axios.get('/bilibili/-/check?key=' + qrcodeKey.value).then(({data}) => {
      if (data === 0) {
        success()
      } else if (data !== 1) {
        fail(data)
      }
    })
  } else {
    fail(2)
  }
}

const success = () => {
  ElMessage.success("登录成功")
  clearInterval(timer)
  timer = 0
  getBilibiliCookie()
  loadUser()
  loginVisible.value = false
}

const fail = (code: number) => {
  if (code === 86038 || code === 2) {
    ElMessage.error("二维码已失效，重新生成")
    scanLogin()
  } else if (code === 86090) {
    ElMessage.error("二维码已扫码未确认")
  } else {
    ElMessage.error("登录失败")
  }

  clearInterval(timer)
  timer = 0
}

const cancel = () => {
  loginVisible.value = false
  clearInterval(timer)
  timer = 0
}

const getHeartbeat = () => {
  axios.get('/settings/bilibili_heartbeat').then(({data}) => {
    heartbeat.value = data.value === 'true'
  })
}

const getSearchable = () => {
  axios.get('/settings/bilibili_searchable').then(({data}) => {
    searchable.value = data.value !== 'false'
  })
}

const getDash = () => {
  axios.get('/settings/bilibili_dash').then(({data}) => {
    dash.value = data.value === 'true'
  })
}

const getBilibiliCookie = () => {
  axios.get('/settings/bilibili_cookie').then(({data}) => {
    bilibiliCookie.value = data.value
  })
}

const getFnval = () => {
  axios.get('/settings/bilibili_fnval').then(({data}) => {
    let val = +data.value
    if (!val) {
      val = 2512
    }
    let num = 64
    for (let i = 0; i < checks.value.length; i++) {
      checks.value[i] = (val & num) != 0
      num *= 2
    }
  })
}

const load = () => {
  return axios.get('/nav').then(({data}) => {
    list.value = data
    list.value.sort((a, b) => a.order - b.order)
    changed.value = false
    return data
  })
}

const loadUser = () => {
  axios.get('/bilibili/-/status').then(({data}) => {
    userInfo.value = data
  })
}

onMounted(() => {
  loadUser()
  getHeartbeat()
  getSearchable()
  getBilibiliCookie()
  getDash()
  getFnval()
  load().then(() => {
    rowDrop()
  })
})
</script>

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
