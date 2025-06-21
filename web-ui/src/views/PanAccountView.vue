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
          {{ scope.row.id + 4000 }}
        </template>
      </el-table-column>
      <el-table-column prop="type" label="类型" sortable width="150">
        <template #default="scope">
          <span v-if="scope.row.type=='QUARK'">夸克网盘</span>
          <span v-else-if="scope.row.type=='UC'">UC网盘</span>
          <span v-else-if="scope.row.type=='QUARK_TV'">夸克TV</span>
          <span v-else-if="scope.row.type=='UC_TV'">UC TV</span>
          <span v-else-if="scope.row.type=='PAN115'">115云盘</span>
          <span v-else-if="scope.row.type=='OPEN115'">115 Open(移除)</span>
          <span v-else-if="scope.row.type=='THUNDER'">迅雷云盘</span>
          <span v-else-if="scope.row.type=='CLOUD189'">天翼云盘</span>
          <span v-else-if="scope.row.type=='PAN139'">移动云盘</span>
          <span v-else-if="scope.row.type=='PAN123'">123网盘</span>
          <span v-else-if="scope.row.type=='BAIDU'">百度网盘</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" sortable width="200"/>
      <el-table-column label="路径">
        <template #default="scope">
          <router-link :to="'/vod' + fullPath(scope.row)">
            {{ fullPath(scope.row) }}
          </router-link>
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
      <el-table-column prop="master" label="禁用？" width="100">
        <template #default="scope">
          <el-icon v-if="scope.row.disabled">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="master" label="开启代理？" width="120">
        <template #default="scope">
          <el-icon v-if="scope.row.useProxy">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="concurrency" label="线程数" width="110"/>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle" width="60%">
      <el-form :model="form" label-width="120">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.type" class="ml-4">
            <el-radio label="QUARK" size="large">夸克网盘</el-radio>
            <el-radio label="UC" size="large">UC网盘</el-radio>
            <el-radio label="QUARK_TV" size="large">夸克TV</el-radio>
            <el-radio label="UC_TV" size="large">UC TV</el-radio>
            <el-radio label="PAN115" size="large">115云盘</el-radio>
            <el-radio label="THUNDER" size="large">迅雷云盘</el-radio>
            <el-radio label="CLOUD189" size="large">天翼云盘</el-radio>
            <el-radio label="PAN139" size="large">移动云盘</el-radio>
            <el-radio label="PAN123" size="large">123网盘</el-radio>
            <el-radio label="BAIDU" size="large">百度网盘</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Cookie" required v-if="form.type=='QUARK'||form.type=='UC'||form.type=='PAN115'||form.type=='BAIDU'">
          <el-input v-model="form.cookie" @change="getInfo" type="textarea" :rows="5"/>
          <span v-if="form.type=='QUARK'">
            <a href="https://pan.quark.cn/" target="_blank">夸克网盘</a>
            <span class="hint"></span>
            <el-button type="primary" @click="showQrCode">扫码获取</el-button>
          </span>

          <span v-if="form.type=='UC'">
            <a href="https://drive.uc.cn/" target="_blank">UC网盘</a>
            <span class="hint"></span>
            <el-button type="primary" @click="showQrCode">扫码获取</el-button>
          </span>

          <span v-if="form.type=='PAN115'">
            <a href="https://115.com/" target="_blank">115云盘</a>
            <span class="hint"></span>
            <el-button type="primary" @click="show115QrCode">扫码获取</el-button>
          </span>

          <span v-if="form.type=='BAIDU'">
            <a href="https://pan.baidu.com/disk/main" target="_blank">百度网盘</a>
            <span class="hint">只需要BDUSS</span>
          </span>
        </el-form-item>
        <el-form-item label="Token" v-if="form.type=='PAN139'" required>
          <el-input v-model="form.token" type="textarea" :rows="3"/>
          <a href="https://yun.139.com/" target="_blank">移动云盘</a>
          <div class="hint"></div>
          <a href="https://alist.nn.ci/zh/guide/drivers/139.html" target="_blank">使用说明</a>
        </el-form-item>
<!--        <el-form-item label="Refresh Token" required v-if="form.type=='OPEN115'">-->
<!--          <el-input v-model="form.token"/>-->
<!--          <a href="https://alist.nn.ci/zh/tool/115/token" target="_blank">获取刷新令牌</a>-->
<!--        </el-form-item>-->
        <el-form-item label="Token" v-if="form.type=='PAN115'">
          <el-input v-model="form.token"/>
        </el-form-item>
        <el-form-item label="Token" v-if="form.type=='QUARK_TV'||form.type=='UC_TV'" required>
          <el-input v-model="form.token" type="textarea" :rows="3"/>
          <el-button type="primary" @click="showQrCode">扫码获取</el-button>
        </el-form-item>
        <el-form-item label="认证令牌" v-if="form.type=='BAIDU'" required>
          <el-input v-model="form.addition.access_token"/>
          <el-button type="primary" @click="copyLink">获取认证令牌</el-button>
        </el-form-item>
        <el-form-item label="用户名" v-if="form.type=='THUNDER'||form.type=='CLOUD189'||form.type=='PAN123'" required>
          <el-input v-model="form.username" :placeholder="form.type=='THUNDER'?'+86 12345678900':''"/>
        </el-form-item>
        <el-form-item label="密码" v-if="form.type=='THUNDER'||form.type=='CLOUD189'||form.type=='PAN123'" required>
          <el-input type="password" show-password v-model="form.password"/>
        </el-form-item>
        <el-form-item label="验证码" v-if="form.type=='THUNDER'||form.type=='CLOUD189'">
          <el-input v-model="form.token"/>
        </el-form-item>
        <el-form-item label="保险箱密码" v-if="form.type=='THUNDER'">
          <el-input type="password" show-password v-model="form.safePassword"/>
        </el-form-item>
        <el-form-item label="文件夹ID">
          <el-input v-model="form.folder"/>
        </el-form-item>
        <el-form-item label="删除码" v-if="form.type=='PAN115'">
          <el-input type="password" show-password v-model="form.addition.delete_code"/>
        </el-form-item>
        <el-form-item v-if="form.type=='PAN115'" label="分页大小">
          <el-input-number :min="100" :max="1500" v-model="form.addition.page_size"/>
        </el-form-item>
        <el-form-item v-if="form.type=='PAN115'" label="请求限速">
          <el-input-number :min="1" :max="4" v-model="form.addition.limit_rate"/>
        </el-form-item>
        <el-form-item v-if="form.type=='PAN115'||form.type=='QUARK'||form.type=='UC'||form.type=='BAIDU'" label="加速代理">
          <el-switch
            v-model="form.useProxy"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
          <span class="hint">服务端多线程加速，网页播放强制开启</span>
        </el-form-item>
        <el-form-item v-if="form.type=='PAN115'||form.type=='QUARK'||form.type=='UC'||form.type=='BAIDU'" label="代理线程数">
          <el-input-number :min="1" :max="16" v-model="form.concurrency"/>
        </el-form-item>
        <el-form-item label="主账号" v-if="!driverRoundRobin&&form.type!='OPEN115'&&form.type!='QUARK_TV'&&form.type!='UC_TV'">
          <el-switch
            v-model="form.master"
            inline-prompt
            active-text="是"
            inactive-text="否"
          />
          <span class="hint">主账号用来观看分享</span>
        </el-form-item>
        <el-form-item label="自动签到" v-if="form.type=='CLOUD189'">
          <el-switch
            v-model="form.addition.auto_checkin"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
        </el-form-item>
        <el-form-item label="禁用账号">
          <el-switch
            v-model="form.disabled"
            inline-prompt
            active-text="是"
            inactive-text="否"
          />
        </el-form-item>
        <span style="margin-left: 72px" v-if="form.name">完整路径： {{ fullPath(form) }}</span>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除网盘账号" width="30%">
      <p>是否删除网盘账号 - {{ form.id + 4000 }}</p>
      <p> {{ getTypeName(form.type) }} ： {{ form.name }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteAccount">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="qrModel" title="扫码登陆" width="40%">
      <img alt="qr" :src="'data:image/jpeg;base64,' + qr.qr_data"/>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="qrModel=false">取消</el-button>
        <el-button @click="showQrCode">刷新二维码</el-button>
        <el-button type="primary" @click="getRefreshToken">我已扫码</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="qr115Model" title="扫码登陆" width="40%">
      <el-form-item label="APP类型" label-width="120" required>
        <el-select v-model="app" @change="refreshQrCode">
          <el-option
            v-for="item in apps"
            :key="item.value"
            :label="item.value"
            :value="item.value"
          />
        </el-select>
        <div class="hint">注意：这会把已经登录的相同 app 踢下线</div>
      </el-form-item>
      <img id="qrcode" alt="qrcode" :src="qrcode"/>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="qr115Model=false">取消</el-button>
        <el-button @click="refreshQrCode">刷新二维码</el-button>
        <el-button type="primary" @click="loadResult">我已扫码</el-button>
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
import clipBorad from "vue-clipboard3";

let {toClipboard} = clipBorad();

const updateAction = ref(false)
const dialogTitle = ref('')
const accounts = ref([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const qrModel = ref(false)
const qr115Model = ref(false)
const driverRoundRobin = ref(false)
const form = ref({
  id: 0,
  type: 'QUARK',
  name: '',
  cookie: '',
  token: '',
  addition: {
    page_size: 1000,
    limit_rate: 2,
    access_token: '',
    delete_code: '',
    auto_checkin: false,
  },
  username: '',
  password: '',
  safePassword: '',
  folder: '',
  concurrency: 2,
  useProxy: false,
  disabled: false,
  master: false,
})
const qr = ref({
  qr_data: '',
  query_token: '',
})

const app = ref('alipaymini')
const uid = ref('')
const qrcode = ref('')
const apps = [
  {
    "value": "wechatmini",
    "label": "115生活(微信小程序)"
  },
  {
    "value": "alipaymini",
    "label": "115生活(支付宝小程序)"
  },
  {
    "value": "115ios",
    "label": "115(iOS端)"
  },
  {
    "value": "115ipad",
    "label": "115(iPad端)"
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
    "value": "tv",
    "label": "115网盘(Android电视端) "
  },
  {
    "value": "web",
    "label": "网页版"
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
    addition: {
      page_size: 1000,
      limit_rate: 2,
      access_token: '',
      delete_code: '',
      auto_checkin: false,
    },
    username: '',
    password: '',
    safePassword: '',
    folder: '',
    concurrency: 2,
    useProxy: false,
    disabled: false,
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
  if (type == 'QUARK_TV') {
    return '夸克TV网盘'
  }
  if (type == 'UC_TV') {
    return 'UC TV网盘'
  }
  if (type == 'PAN115') {
    return '115云盘'
  }
  if (type == 'OPEN115') {
    return '115 Open'
  }
  if (type == 'THUNDER') {
    return '迅雷云盘'
  }
  if (type == 'CLOUD189') {
    return '天翼云盘'
  }
  if (type == 'PAN139') {
    return '移动云盘'
  }
  if (type == 'PAN123') {
    return '123网盘'
  }
  if (type == 'BAIDU') {
    return '百度网盘'
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
  } else if (share.type == 'QUARK_TV') {
    return '/我的夸克网盘/' + path
  } else if (share.type == 'UC_TV') {
    return '/我的UC网盘/' + path
  } else if (share.type == 'PAN115') {
    return '/115云盘/' + path
  } else if (share.type == 'OPEN115') {
    return '/115网盘/' + path
  } else if (share.type == 'THUNDER') {
    return '/我的迅雷云盘/' + path
  } else if (share.type == 'CLOUD189') {
    return '/我的天翼云盘/' + path
  } else if (share.type == 'PAN139') {
    return '/我的移动云盘/' + path
  } else if (share.type == 'PAN123') {
    return '/我的123网盘/' + path
  } else if (share.type == 'BAIDU') {
    return '/我的百度网盘/' + path
  } else {
    return '/网盘/' + path
  }
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新网盘账号 - ' + data.name
  updateAction.value = true
  form.value = Object.assign({}, data, {addition: JSON.parse(data.addition || '{}')})
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
}

const handleConfirm = () => {
  const data = Object.assign({}, form.value, {addition: JSON.stringify(form.value.addition)})
  const url = updateAction.value ? '/api/pan/accounts/' + form.value.id : '/api/pan/accounts'
  axios.post(url, data).then(() => {
    formVisible.value = false
    if (accounts.value.length === 0) {
      ElMessage.success('添加成功')
    } else {
      ElMessage.success('更新成功')
    }
    load()
  })
}

const showQrCode = () => {
  axios.post('/api/pan/accounts/-/qr?type=' + form.value.type).then(({data}) => {
    qr.value = data
    qrModel.value = true
  })
}

const getInfo = () => {
  if (!form.value.cookie) {
    return
  }
  const data = Object.assign({}, form.value, {addition: JSON.stringify(form.value.addition)})
  axios.post('/api/pan/accounts/-/info', data).then(({data}) => {
    if (data && data.name) {
      ElMessage.success('Cookie有效：' + data.name)
      if (!form.value.name) {
        form.value.name = data.name
      }
    } else {
      ElMessage.error('Cookie无效')
    }
  })
}

const copyLink = () => {
  const url = 'https://openapi.baidu.com/oauth/2.0/authorize?response_type=token&scope=basic,netdisk&client_id=IlLqBbU3GjQ0t46TRwFateTprHWl39zF&redirect_uri=oob&confirm_login=0'
  toClipboard(url).then(() => {
    ElMessage.success('链接已复制，在新页面打开')
  })
}

const getRefreshToken = () => {
  axios.post('/api/pan/accounts/-/token?type=' + form.value.type + '&queryToken=' + qr.value.query_token).then(({data}) => {
    if (form.value.type == 'QUARK' || form.value.type == 'UC') {
      form.value.cookie = data.cookie
    } else {
      form.value.token = data.token
    }
    if (!form.value.name) {
      form.value.name = data.name
    }

    qrModel.value = false
  })
}

const show115QrCode = () => {
  axios.get('/api/pan115/token').then(({data}) => {
    uid.value = data.data.uid
    qrcode.value = `https://qrcodeapi.115.com/api/1.0/${app.value}/1.0/qrcode?uid=${uid.value}`
    qr115Model.value = true
  })
}

const refreshQrCode = () => {
  qrcode.value = `https://qrcodeapi.115.com/api/1.0/${app.value}/1.0/qrcode?uid=${uid.value}`
}

const loadResult = async () => {
  return axios.get(`/api/pan115/result?app=${app.value}&uid=${uid.value}`).then(({data}) => {
    if (data.error) {
      ElMessage.warning(data.error)
      return
    }

    qr115Model.value = false
    form.value.cookie = Object.entries(data.data.cookie).map(([k, v]) => `${k}=${v}`).join("; ")
    if (!form.value.name) {
      form.value.name = data.data.user_name
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
  axios.get('/api/settings/driver_round_robin').then(({data}) => {
    driverRoundRobin.value = data.value === 'true'
  })
})
</script>

<style scoped>
.space {
  margin-bottom: 6px;
}

.json pre {
  height: 600px;
  overflow: scroll;
}
</style>
