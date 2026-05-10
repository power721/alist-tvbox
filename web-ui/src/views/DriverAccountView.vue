<template>
  <div class="list">
    <h1>网盘账号列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button @click="openConfig">配置</el-button>
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
        <el-form-item label="Cookie" required v-if="supportCookie(form.type)">
          <el-input v-model="form.cookie" @change="getInfo" type="textarea" :rows="5"/>
          <span v-if="form.type=='QUARK'||form.type=='QUARK_TV'">
            <a href="https://pan.quark.cn/" target="_blank">夸克网盘</a>
            <span class="hint"></span>
            <el-button type="primary" @click="showQrCodeForCookie">扫码获取</el-button>
          </span>

          <span v-if="form.type=='UC'||form.type=='UC_TV'">
            <a href="https://drive.uc.cn/" target="_blank">UC网盘</a>
            <span class="hint"></span>
            <el-button type="primary" @click="showQrCodeForCookie">扫码获取</el-button>
          </span>

          <span v-if="form.type=='PAN115'">
            <a href="https://115.com/" target="_blank">115云盘</a>
            <span class="hint"></span>
            <el-button type="primary" @click="show115QrCode">扫码获取</el-button>
          </span>

          <span v-if="form.type=='BAIDU'">
            <a href="https://pan.baidu.com/disk/main" target="_blank">百度网盘</a>
            <span class="hint">需要完整Cookie</span>
          </span>

          <span v-if="form.type=='CLOUD189'">
            <a href="https://cloud.189.cn/web/main/" target="_blank">天翼云盘</a>
            <span class="hint">可以不填写，自动登陆获取</span>
          </span>
          <el-button class="hint" type="primary" @click="getInfo" v-if="form.cookie">校验Cookie</el-button>
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
        <el-form-item label="认证令牌" v-if="form.type=='BAIDU'">
          <el-input v-model="form.addition.access_token" @change="fixBaiduToken"/>
          <el-button type="primary" @click="copyLink">获取认证令牌</el-button>
          <div class="hint">通过认证后复制浏览器链接填入</div>
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
        <el-form-item label="类型" v-if="form.type=='PAN139'">
          <el-select v-model="form.addition.type">
            <el-option
              v-for="item in pan139Typess"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="视频类型" v-if="form.type=='QUARK_TV'||form.type=='UC_TV'">
          <el-select v-model="form.addition.link_method">
            <el-option
              v-for="item in tvLinkMethod"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="家庭云ID" v-if="form.type=='PAN139'">
          <el-input v-model="form.addition.cloud_id"/>
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
        <el-form-item v-if="supportProxy(form.type)" label="加速代理">
          <el-switch
            v-model="form.useProxy"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
          <span class="hint">服务端多线程加速，网页播放强制开启</span>
        </el-form-item>
        <el-form-item v-if="supportProxy(form.type)" label="代理线程数">
          <el-input-number :min="1" :max="64" v-model="form.concurrency"/>
        </el-form-item>
        <el-form-item v-if="supportProxy(form.type)" label="分片大小">
          <el-input-number :min="64" :max="4096" v-model="form.addition.chunk_size"/>
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

    <el-dialog v-model="configVisible" title="网盘账号配置" width="60%">
      <div class="proxy-config-grid">
        <div class="proxy-config-row proxy-config-head">
          <span>类型</span>
          <span>启用</span>
          <span>并发数</span>
          <span>分片大小(KB)</span>
        </div>
        <div class="proxy-config-row" v-for="item in driveTypes" :key="item.key">
          <span>{{ item.label }}</span>
          <el-switch
            v-model="localProxyConfig[item.key].enabled"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
          <el-input-number
            v-model="localProxyConfig[item.key].concurrency"
            :min="1"
            :max="64"
          />
          <el-input-number
            v-model="localProxyConfig[item.key].chunk_size"
            :min="256"
            :step="256"
          />
        </div>
      </div>
      <div class="config-actions">
        <el-button
          type="primary"
          :loading="savingLocalProxyConfig"
          @click="saveLocalProxyConfig"
        >
          保存代理配置
        </el-button>
      </div>
      <el-divider>离线下载</el-divider>
      <el-form label-width="140">
        <el-form-item label="开启离线下载">
          <el-switch
            v-model="offlineDownloadConfig.enabled"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
        </el-form-item>
        <el-form-item label="网盘类型">
          <el-select v-model="offlineDownloadConfig.driverType" :disabled="!offlineDownloadConfig.enabled">
            <el-option label="115云盘" value="PAN115"/>
          </el-select>
        </el-form-item>
        <el-form-item label="网盘账号">
          <el-select
            v-model="offlineDownloadConfig.accountId"
            clearable
            :disabled="!offlineDownloadConfig.enabled"
          >
            <el-option
              v-for="item in offlineAccounts"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="当前挂载目录">
          <el-input :model-value="offlineMountFolder" readonly/>
        </el-form-item>
        <el-form-item v-if="offlineQuotaText" label="115本月配额">
          <span>{{ offlineQuotaText }}</span>
        </el-form-item>
      </el-form>
      <div class="config-actions">
        <el-button
          type="primary"
          :loading="savingOfflineDownloadConfig"
          @click="saveOfflineDownloadConfig"
        >
          保存离线下载配置
        </el-button>
      </div>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="configVisible = false">取消</el-button>
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
            :label="item.label"
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
import {computed, onMounted, ref, watch} from 'vue'
import {Check, Close} from '@element-plus/icons-vue'
import axios from "axios"
import {ElMessage} from "element-plus";
import clipBorad from "vue-clipboard3";

let {toClipboard} = clipBorad();

type CloudDriveType = 'ALI' | 'QUARK' | 'UC' | 'PAN115' | 'PAN123' | 'PAN139' | 'BAIDU'

type LocalProxyItem = {
  enabled: boolean
  concurrency: number
  chunk_size: number
}

type LocalProxyConfig = Record<CloudDriveType, LocalProxyItem>

type OfflineDownloadConfig = {
  enabled: boolean
  driverType: 'PAN115'
  accountId: number | null
}

type OfflineDownloadQuota = {
  surplus: number
  count: number
  used: number
} | null

type DriverAccountItem = {
  id: number
  type: string
  name: string
  folder: string
}

const updateAction = ref(false)
const dialogTitle = ref('')
const accounts = ref<DriverAccountItem[]>([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const configVisible = ref(false)
const qrModel = ref(false)
const qr115Model = ref(false)
const driverRoundRobin = ref(false)
const driveTypes: Array<{ key: CloudDriveType; label: string }> = [
  {key: 'ALI', label: '阿里云盘'},
  {key: 'QUARK', label: '夸克网盘'},
  {key: 'UC', label: 'UC网盘'},
  {key: 'PAN115', label: '115云盘'},
  {key: 'PAN123', label: '123网盘'},
  {key: 'PAN139', label: '移动云盘'},
  {key: 'BAIDU', label: '百度网盘'},
]
const form = ref({
  id: 0,
  type: 'QUARK',
  name: '',
  cookie: '',
  token: '',
  addition: {
    chunk_size: 256,
    page_size: 1000,
    limit_rate: 2,
    access_token: '',
    delete_code: '',
    cloud_id: '',
    link_method: 'download',
    type: 'personal_new',
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
const qrType = ref('')
const defaultLocalProxyConfig = (): LocalProxyConfig => ({
  ALI: {enabled: true, concurrency: 20, chunk_size: 1024},
  QUARK: {enabled: true, concurrency: 20, chunk_size: 1024},
  UC: {enabled: true, concurrency: 10, chunk_size: 256},
  PAN115: {enabled: true, concurrency: 2, chunk_size: 1024},
  PAN123: {enabled: true, concurrency: 4, chunk_size: 256},
  PAN139: {enabled: true, concurrency: 4, chunk_size: 256},
  BAIDU: {enabled: true, concurrency: 5, chunk_size: 2048},
})
const localProxyConfig = ref<LocalProxyConfig>(defaultLocalProxyConfig())
const offlineDownloadConfig = ref<OfflineDownloadConfig>({
  enabled: false,
  driverType: 'PAN115',
  accountId: null,
})
const offlineDownloadQuota = ref<OfflineDownloadQuota>(null)
const savingLocalProxyConfig = ref(false)
const savingOfflineDownloadConfig = ref(false)
const offlineAccounts = computed(() => accounts.value.filter((item) => item.type === offlineDownloadConfig.value.driverType))
const offlineMountFolder = computed(() => {
  const account = offlineAccounts.value.find((item) => item.id === offlineDownloadConfig.value.accountId)
  return account ? fullPath(account) : ''
})
const offlineQuotaText = computed(() => {
  if (!offlineDownloadQuota.value) {
    return ''
  }
  return `本月配额：剩${offlineDownloadQuota.value.surplus}/总${offlineDownloadQuota.value.count}个`
})

watch(() => offlineDownloadConfig.value.driverType, () => {
  const exists = offlineAccounts.value.some((item) => item.id === offlineDownloadConfig.value.accountId)
  if (!exists) {
    offlineDownloadConfig.value.accountId = null
  }
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

const pan139Typess = [
  {
    "value": "personal_new",
    "label": "个人盘"
  },
  {
    "value": "family",
    "label": "家庭云"
  },
  {
    "value": "group",
    "label": "共享群"
  },
]

const tvLinkMethod = [
  {
    "value": "download",
    "label": "原画"
  },
  {
    "value": "streaming",
    "label": "转码"
  },
]

const supportCookie = (type: string) => {
  return type == 'PAN115'
    || type == 'QUARK'
    || type == 'QUARK_TV'
    || type == 'UC'
    || type == 'UC_TV'
    || type == 'BAIDU'
    || type == 'CLOUD189'
}

const supportProxy = (type: string) => {
  return type == 'PAN115'
    || type == 'QUARK'
    || type == 'QUARK_TV'
    || type == 'UC'
    || type == 'UC_TV'
    || type == 'BAIDU'
    || type == 'PAN139'
}

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
      chunk_size: 256,
      page_size: 1000,
      limit_rate: 2,
      access_token: '',
      delete_code: '',
      cloud_id: '',
      link_method: 'download',
      type: 'personal_new',
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

const normalizeLocalProxyConfig = (value: any): LocalProxyConfig => {
  const defaults = defaultLocalProxyConfig()
  for (const item of driveTypes) {
    const current = value?.[item.key] || {}
    defaults[item.key] = {
      enabled: current.enabled ?? defaults[item.key].enabled,
      concurrency: current.concurrency ?? defaults[item.key].concurrency,
      chunk_size: current.chunk_size ?? defaults[item.key].chunk_size,
    }
  }
  return defaults
}

const loadLocalProxyConfig = async () => {
  const {data} = await axios.get('/api/settings/local_proxy_config')
  if (!data || !data.value) {
    localProxyConfig.value = defaultLocalProxyConfig()
    return
  }

  try {
    localProxyConfig.value = normalizeLocalProxyConfig(JSON.parse(data.value))
  } catch (e) {
    localProxyConfig.value = defaultLocalProxyConfig()
  }
}

const loadOfflineDownloadConfig = async () => {
  const {data} = await axios.get('/api/offline_download/config')
  offlineDownloadConfig.value = {
    enabled: !!data?.enabled,
    driverType: 'PAN115',
    accountId: data?.accountId ?? null,
  }
}

const loadOfflineDownloadQuota = async () => {
  offlineDownloadQuota.value = null
  if (!offlineDownloadConfig.value.enabled || offlineDownloadConfig.value.accountId == null) {
    return
  }

  await axios.get('/api/offline_download/quota').then(({data}) => {
    offlineDownloadQuota.value = {
      surplus: data?.surplus ?? 0,
      count: data?.count ?? 0,
      used: data?.used ?? 0,
    }
  }).catch(() => {
    offlineDownloadQuota.value = null
  })
}

const openConfig = async () => {
  await loadLocalProxyConfig()
  await loadOfflineDownloadConfig()
  await loadOfflineDownloadQuota()
  configVisible.value = true
}

const updateLocalProxyConfig = () => {
  return axios.post('/api/settings', {
    name: 'local_proxy_config',
    value: JSON.stringify(localProxyConfig.value),
  })
}

const updateOfflineDownloadConfig = () => {
  return axios.post('/api/offline_download/config', offlineDownloadConfig.value)
}

const saveLocalProxyConfig = async () => {
  try {
    savingLocalProxyConfig.value = true
    await updateLocalProxyConfig()
    ElMessage.success('代理配置已保存')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '代理配置保存失败')
  } finally {
    savingLocalProxyConfig.value = false
  }
}

const saveOfflineDownloadConfig = async () => {
  try {
    savingOfflineDownloadConfig.value = true
    await updateOfflineDownloadConfig()
    await loadOfflineDownloadQuota()
    ElMessage.success('离线下载配置已保存')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '离线下载配置保存失败')
  } finally {
    savingOfflineDownloadConfig.value = false
  }
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
  qrType.value = form.value.type
  axios.post('/api/pan/accounts/-/qr?type=' + form.value.type).then(({data}) => {
    qr.value = data
    qrModel.value = true
  })
}

const showQrCodeForCookie = () => {
  // For QUARK_TV and UC_TV, use QUARK and UC type to get cookie
  let type = form.value.type
  if (type === 'QUARK_TV') {
    type = 'QUARK'
  } else if (type === 'UC_TV') {
    type = 'UC'
  }
  qrType.value = type
  axios.post('/api/pan/accounts/-/qr?type=' + type).then(({data}) => {
    qr.value = data
    qrModel.value = true
  })
}

const getInfo = () => {
  if (!form.value.cookie) {
    return
  }
  const data = Object.assign({}, form.value, {addition: JSON.stringify(form.value.addition)})
  axios.post('/api/pan/accounts/-/info?type=cookie', data).then(({data}) => {
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

const fixBaiduToken = () => {
  let token = form.value.addition.access_token
  if (token) {
    let index = token.indexOf('access_token=')
    if (index > -1) {
      token = token.substring(index + 13)
      index = token.indexOf('&')
      if (index > 0) {
        token = token.substring(0, index)
      }
      form.value.addition.access_token = token
    }
  }
}

const getRefreshToken = () => {
  axios.post('/api/pan/accounts/-/token?type=' + qrType.value + '&queryToken=' + qr.value.query_token).then(({data}) => {
    if (qrType.value == 'QUARK' || qrType.value == 'UC') {
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

.proxy-config-grid {
  display: grid;
  gap: 12px;
}

.proxy-config-row {
  display: grid;
  grid-template-columns: 120px 120px 160px 180px;
  align-items: center;
  gap: 12px;
}

.proxy-config-head {
  font-weight: 600;
}

.config-actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
