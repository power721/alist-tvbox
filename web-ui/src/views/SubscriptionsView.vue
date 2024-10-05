<template>
  <div class="subscriptions">
    <h1>订阅列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button @click="handleLogin">登陆电报</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="subscriptions" border style="width: 100%">
      <!--      <el-table-column prop="id" label="ID" sortable width="70"/>-->
      <el-table-column prop="sid" label="订阅ID" sortable width="180"/>
      <el-table-column prop="name" label="名称" sortable width="180"/>
      <el-table-column prop="url" label="原始配置URL" sortable>
        <template #default="scope">
          <a :href="scope.row.url" target="_blank">{{ scope.row.url }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="url" label="TvBox配置地址" sortable>
        <template #default="scope">
          <a :href="currentUrl+'/sub'+token+'/'+scope.row.sid" target="_blank">{{ currentUrl }}/sub{{
              token
            }}/{{ scope.row.sid }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="url" label="多仓聚合地址" sortable>
        <template #default="scope">
          <a :href="currentUrl+'/repo'+token+'/'+scope.row.sid" target="_blank">{{ currentUrl }}/repo{{
              token
            }}/{{ scope.row.sid }}</a>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="handleEdit(scope.row)" v-if="scope.row.id">编辑
          </el-button>
          <el-button link type="primary" size="small" @click="showDetails(scope.row)">数据
          </el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)" v-if="scope.row.id">删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-row>
      猫影视配置接口：
      <a
        :href="currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')+'/open'+token"
        target="_blank">
        {{
          currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')
        }}/open{{ token }}
      </a>
    </el-row>
    <el-row>
      猫影视node配置接口：
      <a
        :href="currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')+'/node'+(token ? token : '/-')+'/index.config.js'"
        target="_blank">
        {{
          currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')
        }}/node{{ token ? token : '/-' }}/index.js.md5
      </a>
    </el-row>
    <el-row>
      PG包本地： {{ pgLocal }}
      PG包远程： {{ pgRemote }}
    </el-row>
    <el-row>
      真心包本地： {{ zxLocal }}
      真心包远程： {{ zxRemote }}
    </el-row>
    <el-row>
      <el-button @click="syncCat">同步文件</el-button>
    </el-row>

    <el-dialog v-model="formVisible" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="订阅ID" label-width="140" required>
          <el-input v-model="form.sid" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="名称" label-width="140" required>
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="配置URL" label-width="140">
          <el-input v-model="form.url" autocomplete="off" placeholder="支持多个，逗号分割。留空使用默认配置。"/>
        </el-form-item>
        <el-form-item label="排序字段" label-width="140">
          <el-input v-model="form.sort" autocomplete="off" placeholder="留空保持默认排序"/>
        </el-form-item>
        <el-form-item label="定制" label-width="140">
          <el-input v-model="form.override" type="textarea" rows="15"/>
          <a href="https://www.json.cn/" target="_blank">JSON验证</a>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="dialogTitle" :fullscreen="true">
      <div>
        <p>配置URL：</p>
        <a :href="form.url" target="_blank">{{ form.url }}</a>
      </div>
      <h2>JSON数据</h2>
      <el-scrollbar height="800px">
        <json-viewer :value="jsonData" expanded copyable show-double-quotes :show-array-index="false"
                     :expand-depth=5></json-viewer>
      </el-scrollbar>
      <div class="json"></div>
      <template #footer>
      <span class="dialog-footer">
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除订阅" width="30%">
      <p>是否删除订阅 - {{ form.name }}</p>
      <p>{{ form.url }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSub">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="tgVisible" title="登陆Telegram" width="60%">
      <el-form>
        <el-form-item label="电话号码" label-width="140" required v-if="tgPhase==1">
          <el-input v-model="tgPhone" autocomplete="off"/>
          <el-button @click="sendTgPhone">输入</el-button>
        </el-form-item>
        <el-form-item label="验证码" label-width="140" required v-if="tgPhase==3">
          <el-input v-model="tgCode" autocomplete="off"/>
          <el-button @click="sendTgCode">输入</el-button>
        </el-form-item>
        <el-form-item label="密码" label-width="140" required v-if="tgPhase==5">
          <el-input v-model="tgPassword" autocomplete="off"/>
          <el-button @click="sendTgPassword">输入</el-button>
        </el-form-item>
        <span v-if="tgPhase==9">登陆成功</span>
        <div v-if="user.id">
          <div>用户ID： {{user.id}}</div>
          <div>用户名： {{user.username}}</div>
          <div>姓名： {{user.first_name}} {{user.last_name}}</div>
        </div>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button type="primary" @click="login">登陆</el-button>
        <el-button @click="cancelLogin">取消</el-button>
      </span>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"
import {ElMessage} from "element-plus";
import {onUnmounted} from "@vue/runtime-core";

const currentUrl = window.location.origin
const tgPhase = ref(0)
const tgPhone = ref('')
const tgCode = ref('')
const tgPassword = ref('')
const token = ref('')
const pgLocal = ref('')
const pgRemote = ref('')
const zxLocal = ref('')
const zxRemote = ref('')
const updateAction = ref(false)
const dialogTitle = ref('')
const jsonData = ref({})
const subscriptions = ref([])
const detailVisible = ref(false)
const formVisible = ref(false)
const dialogVisible = ref(false)
const tgVisible = ref(false)
const form = ref({
  id: 0,
  sid: '',
  name: '',
  url: '',
  sort: '',
  override: ''
})
const user = ref({
  id: 0,
  username: '',
  first_name: '',
  last_name: '',
  phone: ''
})
let timer = 0

const handleLogin = () => {
  axios.get('/api/telegram/user').then(({data}) => {
    user.value = data
  })
  tgVisible.value = true
}

const login = () => {
  axios.post('/api/telegram/login')
  timer = setInterval(() => {
    axios.get('/api/settings/tg_phase').then(({data}) => {
      tgPhase.value = +data.value
      if (tgPhase.value > 8) {
        clearInterval(timer)
        axios.get('/api/telegram/user').then(({data}) => {
          user.value = data
        })
      }
    })
  }, 1000)
  setTimeout(() => {
    clearInterval(timer)
  }, 120_000)
}

const cancelLogin = () => {
  clearInterval(timer)
  tgVisible.value = false
}

const handleAdd = () => {
  dialogTitle.value = '添加订阅'
  updateAction.value = false
  form.value = {
    id: 0,
    sid: '',
    name: '',
    url: '',
    sort: '',
    override: ''
  }
  formVisible.value = true
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新订阅 - ' + data.name
  updateAction.value = true
  form.value = {
    id: data.id,
    sid: data.sid,
    name: data.name,
    url: data.url,
    sort: data.sort,
    override: data.override
  }
  formVisible.value = true
}

const showDetails = (data: any) => {
  form.value = data
  dialogTitle.value = '订阅数据 - ' + data.name
  axios.get('/sub' + token.value + '/' + data.sid).then(({data}) => {
    jsonData.value = data
    detailVisible.value = true
  })
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSub = () => {
  dialogVisible.value = false
  axios.delete('/api/subscriptions/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const sendTgPhone = () => {
  axios.post('/api/settings', {name: 'tg_phone', value: tgPhone.value})
}

const sendTgCode = () => {
  axios.post('/api/settings', {name: 'tg_code', value: tgCode.value})
}

const sendTgPassword = () => {
  axios.post('/api/settings', {name: 'tg_password', value: tgPassword.value})
}

const handleConfirm = () => {
  axios.post('/api/subscriptions', form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const syncCat = () => {
  axios.post('/api/cat/sync').then(({data}) => {
    if (data) {
      ElMessage.warning('同步失败')
    } else {
      ElMessage.success('同步成功')
      setTimeout(loadVersion, 1000)
    }
  })
}

const load = () => {
  axios.get('/api/subscriptions').then(({data}) => {
    subscriptions.value = data
  })
}

const loadVersion = () => {
  axios.get("/pg/version").then(({data}) => {
    pgLocal.value = data.local
    pgRemote.value = data.remote
  })
  axios.get("/zx/version").then(({data}) => {
    zxLocal.value = data.local
    zxRemote.value = data.remote
  })
}

onMounted(() => {
  axios.get('/api/token').then(({data}) => {
    token.value = data ? '/' + (data + '').split(',')[0] : ''
    load()
    loadVersion()
    axios.get('/api/settings/tg_phase').then(({data}) => {
      tgPhase.value = data.value
    })
  })
})

onUnmounted(() => {
  clearInterval(timer)
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
