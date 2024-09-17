<template>
  <div class="list">
    <h1>网盘账号列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="accounts" border style="width: 100%">
      <el-table-column prop="id" label="ID" sortable width="70">
        <template #default="scope">
          {{ scope.row.id + 9000 }}
        </template>
      </el-table-column>
      <el-table-column prop="type" label="类型" sortable width="150">
        <template #default="scope">
          <span v-if="scope.row.type=='QUARK'">夸克网盘</span>
          <span v-else-if="scope.row.type=='UC'">UC网盘</span>
          <span v-else-if="scope.row.type=='PAN115'">115网盘</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" sortable width="200"/>
      <el-table-column label="路径">
        <template #default="scope">
          {{ fullPath(scope.row) }}
        </template>
      </el-table-column>
      <el-table-column prop="master" label="主账号？" width="120">
        <template #default="scope">
          <el-icon v-if="scope.row.master">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="reloadStorage(scope.row.id + 9000)">重新加载</el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle" width="60%">
      <el-form :model="form">
        <el-form-item label="名称" label-width="140" required>
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="类型" label-width="120" required>
          <el-radio-group v-model="form.type" class="ml-4" @change="onTypeChange">
            <el-radio label="QUARK" size="large">夸克网盘</el-radio>
            <el-radio label="UC" size="large">UC网盘</el-radio>
            <el-radio label="PAN115" size="large">115网盘</el-radio>
            <el-radio label="PAN115_SCAN" size="large">115扫码</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="APP类型" v-if="form.type=='PAN115'||form.type=='PAN115_SCAN'" label-width="120" required>
          <el-select v-model="app">
            <el-option
              v-for="item in apps"
              :key="item.value"
              :label="item.value"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="二维码" v-if="form.type=='PAN115_SCAN'">
          <img id="qrcode" alt="qrcode" :src="qrcode"/>
          <span class="hint">{{ statusText}}</span>
        </el-form-item>
        <el-form-item label="Cookie" label-width="140" required>
          <el-input v-model="form.cookie" type="textarea" :rows="5"/>
        </el-form-item>
        <!--        <el-form-item label="Token" label-width="140">-->
        <!--          <el-input v-model="form.token"/>-->
        <!--        </el-form-item>-->
        <el-form-item label="文件夹ID" label-width="140">
          <el-input v-model="form.folder"/>
        </el-form-item>
        <el-form-item label="主账号" label-width="140">
          <el-switch
            v-model="form.master"
            inline-prompt
            active-text="是"
            inactive-text="否"
          />
          <span class="hint">主账号用来观看分享。</span>
        </el-form-item>
        <span v-if="form.name">完整路径： {{ fullPath(form) }}</span>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除网盘账号" width="30%">
      <p>是否删除网盘账号 - {{ form.id + 9000 }}</p>
      <p> {{ getTypeName(form.type) }} ： {{ form.name }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteAccount">删除</el-button>
      </span>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {Check, Close} from '@element-plus/icons-vue'
import axios from "axios"
import {ElMessage} from "element-plus";

let count = 0
const exp = ref(0)
const status = ref(0)
const updateAction = ref(false)
const dialogTitle = ref('')
const app = ref('qandriod')
const qrcode = ref('')
const statusText = ref('等待扫码')
const accounts = ref([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const form = ref({
  id: 0,
  type: 'QUARK',
  name: '',
  cookie: '',
  token: '',
  folder: '',
  addition: '',
  master: false,
})
const apps = [
  {
    "value": "qandriod",
    "label": "115管理(Android端)"
  },
  {
    "value": "web",
    "label": "网页版"
  },
  {
    "value": "ios",
    "label": "115生活(iOS端)"
  },
  {
    "value": "115ios",
    "label": "115(iOS端)"
  },
  {
    "value": "android",
    "label": "115生活(Android端)"
  },
  {
    "value": "115android",
    "label": "115(Android端)"
  },
  {
    "value": "ipad",
    "label": "ipad"
  },
  {
    "value": "115ipad",
    "label": "115(iPad端)"
  },
  {
    "value": "tv",
    "label": "115网盘(Android电视端) "
  },
  {
    "value": "qios",
    "label": "115管理(iOS端) "
  },
  {
    "value": "wechatmini",
    "label": "115生活(微信小程序)"
  },
  {
    "value": "alipaymini",
    "label": "115生活(支付宝小程序)"
  },
  {
    "value": "windows",
    "label": "115生活(Windows端) "
  },
  {
    "value": "mac",
    "label": "115生活(macOS端)"
  },
  {
    "value": "linux",
    "label": "115生活(Linux端)"
  },
]

const handleAdd = () => {
  dialogTitle.value = '添加网盘账号'
  updateAction.value = false
  form.value = {
    id: 0,
    type: 'QUARK',
    name: '',
    cookie: '',
    token: '',
    folder: '',
    addition: '',
    master: false,
  }
  formVisible.value = true
}

const getTypeName = (type: string) => {
  if (type == 'QUARK') {
    return '夸克网盘'
  }
  if (type == 'UC') {
    return 'UC网盘'
  }
  if (type == 'PAN115') {
    return '115网盘'
  }
  return '未知'
}

const fullPath = (share: any) => {
  const path = share.name;
  if (path.startsWith('/')) {
    return path
  }
  if (share.type == 'QUARK') {
    return '/🌞我的夸克网盘/' + path
  } else if (share.type == 'UC') {
    return '/🌞我的UC网盘/' + path
  } else if (share.type == 'PAN115') {
    return '/115网盘/' + path
  } else {
    return '/网盘/' + path
  }
}

const onTypeChange = (type: string) => {
  if (type == 'PAN115_SCAN') {
    axios.get('/api/pan115/token').then(async ({data}) => {
      console.log(data)
      qrcode.value = `https://qrcodeapi.115.com/api/1.0/mac/1.0/qrcode?uid=${data.data.uid}`
      count = 0
      while (count++ < 100) {
        try {
          await loadStatus(data.data.sign, data.data.time, data.data.uid);
        } catch (e) {
          console.error(e);
          continue
        }
        if (status.value == 2) {
          await loadResult(data.data.uid);
          return true;
        } else if (status.value != 0 && status.value != 1)
          return false;
      }
    })
  } else {
    count = 600
  }
}

const loadStatus = (sign: string, time: number, uid: string) => {
  return axios.get(`/api/pan115/status?sign=${sign}&time=${time}&uid=${uid}`).then(({data}) => {
    status.value = data.data.status
    switch (status.value) {
      case 0:
        statusText.value = '等待扫码';
        break;
      case 1:
        statusText.value = '已经扫码';
        break;
      case 2:
        statusText.value = '登陆成功';
        break;
      case -1:
        statusText.value = '二维码过期';
        break;
      case -2:
        statusText.value = '取消登陆';
        break;
      default:
        statusText.value = '登陆终止';
    }
  })
}

const loadResult = async (uid: string) => {
  return axios.get(`/api/pan115/result?app=${app.value}&uid=${uid}`).then(({data}) => {
    form.value.cookie = Object.entries(data.data.cookie).map(([k, v]) => `${k}=${v}`).join("; ")
    if (!form.value.name) {
      form.value.name = data.data.user_name
    }
  })
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新网盘账号 - ' + data.name
  updateAction.value = true
  form.value = Object.assign({}, data)
  formVisible.value = true
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteAccount = () => {
  dialogVisible.value = false
  axios.delete('/api/pan/accounts/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
  count = 600
}

const handleConfirm = () => {
  if (form.value.type == 'PAN115_SCAN') {
    form.value.type = 'PAN115'
  }
  if (form.value.type == 'PAN115') {
    form.value.addition = app.value
  }
  const url = updateAction.value ? '/api/pan/accounts/' + form.value.id : '/api/pan/accounts'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    if (accounts.value.length === 0) {
      ElMessage.success('添加成功')
    } else {
      ElMessage.success('更新成功')
    }
    load()
  })
}

const reloadStorage = (id: number) => {
  axios.post('/api/storages/' + id).then(({data}) => {
    if (data.code == 200) {
      ElMessage.success('加载成功')
    } else {
      ElMessage.error(data.message)
    }
  })
}

const load = () => {
  axios.get('/api/pan/accounts').then(({data}) => {
    accounts.value = data
  })
}

onMounted(() => {
  load()
})
</script>

<style scoped>
.space {
  margin-bottom: 6px;
}

.hint {
  margin-left: 16px;
}

.json pre {
  height: 600px;
  overflow: scroll;
}

#qrcode {
  width: 200px;
  height: 200px;
  display: block;
}
</style>
