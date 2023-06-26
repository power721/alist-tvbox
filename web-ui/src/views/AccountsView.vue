<template>
  <div class="sites">
    <h1>账号列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="accounts" border style="width: 100%">
      <el-table-column prop="id" label="ID" sortable width="70"/>
      <el-table-column prop="nickname" label="昵称" sortable width="180"/>
      <el-table-column prop="autoCheckin" label="自动签到" width="90">
        <template #default="scope">
          <el-icon v-if="scope.row.autoCheckin">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="checkinDays" label="签到次数" width="90"/>
      <el-table-column prop="checkinTime" label="上次签到时间">
        <template #default="scope">
          {{formatTime(scope.row.checkinTime)}}
        </template>
      </el-table-column>
      <el-table-column prop="showMyAli" label="加载我的云盘？" width="150">
        <template #default="scope">
          <el-icon v-if="scope.row.showMyAli">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
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
      <el-table-column prop="master" label="自动清理？" width="120">
        <template #default="scope">
          <el-icon v-if="scope.row.clean">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="showDetails(scope.row)">详情</el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle" width="60%">
      <el-form :model="form">
        <el-form-item label="阿里token" label-width="140">
          <el-input v-model="form.refreshToken" maxlength="128" placeholder="长度32位" autocomplete="off"/>
          <a href="https://alist.nn.ci/zh/guide/drivers/aliyundrive.html" target="_blank">获取阿里token</a><br/>
          <a href="https://aliyuntoken.vercel.app/" class="hint" target="_blank">获取阿里token</a>
        </el-form-item>
        <el-form-item label="开放token" label-width="140">
          <el-input v-model="form.openToken" type="textarea" rows="3" minlength="256" placeholder="长度280位"
                    autocomplete="off"/>
          <a href="https://alist.nn.ci/zh/guide/drivers/aliyundrive_open.html" target="_blank">获取开放token</a>
        </el-form-item>
        <el-form-item label="转存文件夹ID" label-width="140">
          <el-input v-model="form.folderId" placeholder="长度40位" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="加载我的云盘" label-width="140" v-if="form.openToken">
          <el-switch
            v-model="form.showMyAli"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
        </el-form-item>
        <el-form-item label="主账号" label-width="140">
          <el-switch
            v-model="form.master"
            inline-prompt
            active-text="是"
            inactive-text="否"
          />
        </el-form-item>
        <el-form-item label="自动清理">
          <el-switch
            v-model="form.clean"
            inline-prompt
            active-text="是"
            inactive-text="否"
          />
        </el-form-item>
        <el-form-item label="自动签到" label-width="140">
          <el-switch
            v-model="form.autoCheckin"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除站点" width="30%">
      <p>是否删除账号 - {{ form.id }}</p>
      <p>{{ form.nickname }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSite">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="账号详情" width="60%">
      <el-form :model="form" label-width="120px">
        <el-form-item prop="accessToken" label="阿里token">
          <el-input v-model="form.refreshToken" maxlength="128" placeholder="长度32位"/>
          <a href="https://alist.nn.ci/zh/guide/drivers/aliyundrive.html" target="_blank">获取阿里token</a><br/>
          <a href="https://aliyuntoken.vercel.app/" class="hint" target="_blank">获取阿里token</a>
          <span class="hint">更新时间： {{ formatTime(form.refreshTokenTime) }}</span>
        </el-form-item>
        <el-form-item prop="openToken" label="开放token">
          <el-input v-model="form.openToken" type="textarea" rows="3" minlength="256" placeholder="长度280位"/>
          <a href="https://alist.nn.ci/zh/guide/drivers/aliyundrive_open.html" target="_blank">获取开放token</a>
          <span class="hint">创建时间： {{ formatTime(iat) }}</span>
          <span class="hint">更新时间： {{ formatTime(form.openTokenTime) }}</span>
          <span class="hint">过期时间： {{ formatTime(exp) }}</span>
        </el-form-item>
        <el-form-item prop="folderId" label="转存文件夹ID">
          <el-input v-model="form.folderId" placeholder="长度40位"/>
          <a href="https://www.aliyundrive.com/drive" target="_blank">阿里云盘</a>
        </el-form-item>
        <el-form-item label="加载我的云盘" v-if="form.openToken">
          <el-switch
            v-model="form.showMyAli"
            inline-prompt
            active-text="加载"
            inactive-text="关闭"
          />
        </el-form-item>
        <el-form-item label="主账号">
          <el-switch
            v-model="form.master"
            inline-prompt
            active-text="是"
            inactive-text="否"
          />
        </el-form-item>
        <el-form-item label="自动清理">
          <el-switch
            v-model="form.clean"
            inline-prompt
            active-text="是"
            inactive-text="否"
          />
        </el-form-item>
        <el-form-item label="自动签到">
          <el-switch
            v-model="form.autoCheckin"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
        </el-form-item>
        <el-form-item label="上次签到时间" v-if="form.checkinTime">
          <el-input :model-value="formatTime(form.checkinTime)" readonly/>
          <span class="hint" v-if="form.checkinDays">{{ form.nickname }} 本月签到{{ form.checkinDays }}次</span>
        </el-form-item>
        <el-checkbox v-model="forceCheckin" label="强制签到"/>
      </el-form>
      <template #footer>
          <span class="dialog-footer">
            <el-button @click="detailVisible = false">取消</el-button>
          <el-button type="success" @click="clean">清理</el-button>
          <el-button type="success" @click="checkin">签到</el-button>
          <el-button type="primary" @click="handleConfirm">更新</el-button>
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

interface Item {
  path: string
  text: string
}

const iat = ref(0)
const exp = ref(0)
const forceCheckin = ref(false)
const updateAction = ref(false)
const dialogTitle = ref('')
const accounts = ref([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const detailVisible = ref(false)
const form = ref({
  id: 0,
  nickname: '',
  refreshToken: '',
  openToken: '',
  folderId: '',
  autoCheckin: true,
  showMyAli: false,
  master: false,
  clean: false,
  refreshTokenTime: '',
  openTokenTime: '',
  checkinTime: '',
  checkinDays: 1,
})

const formatTime = (value: string | number) => {
  return new Date(value).toLocaleString('zh-cn')
}

const showDetails = (data: any) => {
  form.value = data
  updateAction.value = true
  if (data.openToken) {
    let details = JSON.parse(atob(data.openToken.split('.')[1]))
    iat.value = details.iat * 1000
    exp.value = details.exp * 1000
  }
  detailVisible.value = true
}

const checkin = () => {
  axios.post('/ali-accounts/' + form.value.id + '/checkin?force=' + forceCheckin.value).then(({data}) => {
    form.value.checkinTime = data.checkinTime
    form.value.checkinDays = data.signInCount
    form.value.nickname = data.nickname
    forceCheckin.value = false
    ElMessage.success('签到成功, 本月累计' + data.signInCount + '天')
  })
}

const clean = () => {
  axios.post('/ali-accounts/' + form.value.id + '/clean').then(({data}) => {
    if (data) {
      ElMessage.success('成功清理' + data + '个过期文件')
    } else {
      ElMessage.info('没有可清理的文件')
    }
  })
}

const handleAdd = () => {
  dialogTitle.value = '添加账号'
  updateAction.value = false
  form.value = {
    id: 0,
    nickname: '',
    refreshToken: '',
    openToken: '',
    folderId: '',
    autoCheckin: true,
    showMyAli: false,
    master: false,
    clean: false,
    refreshTokenTime: '',
    openTokenTime: '',
    checkinTime: '',
    checkinDays: 0,
  }
  formVisible.value = true
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSite = () => {
  dialogVisible.value = false
  axios.delete('/ali-accounts/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/ali-accounts/' + form.value.id : '/ali-accounts'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    ElMessage.success('更新成功')
    load()
  })
}

const load = () => {
  axios.get('/ali-accounts').then(({data}) => {
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

.json pre {
  height: 600px;
  overflow: scroll;
}
</style>
