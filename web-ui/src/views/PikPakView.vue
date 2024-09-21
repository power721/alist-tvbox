<template>
  <div class="list">
    <h1>PikPak账号列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="accounts" border style="width: 100%">
<!--      <el-table-column prop="id" label="ID" sortable width="70"/>-->
      <el-table-column prop="nickname" label="昵称" sortable width="180"/>
      <el-table-column prop="username" label="用户名"/>
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
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle" width="60%">
      <el-form :model="form">
        <el-form-item label="昵称" label-width="140" required>
          <el-input v-model="form.nickname" autocomplete="off" placeholder="昵称决定挂载路径"/>
        </el-form-item>
        <el-form-item label="用户名" label-width="140" required>
          <el-input v-model="form.username" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="密码" label-width="140" required>
          <el-input v-model="form.password" type="password" show-password/>
        </el-form-item>
        <el-form-item label="认证平台" label-width="140" required>
          <el-select v-model="form.platform">
              <el-option
                v-for="item in platforms"
                :key="item"
                :label="item"
                :value="item"
              />
          </el-select>
        </el-form-item>
        <el-form-item label="认证方式" label-width="140" required>
          <el-select v-model="form.refreshTokenMethod">
              <el-option
                v-for="item in methods"
                :key="item"
                :label="item"
                :value="item"
              />
          </el-select>
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
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除PikPak账号" width="30%">
      <p>是否删除PikPak账号 - {{ form.id }}</p>
      <p>{{ form.nickname }}</p>
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
import {store} from "@/services/store";
import router from "@/router";

interface Item {
  path: string
  text: string
}

const platforms = ['pc', 'web', 'android']
const methods = ['oauth2', 'http']
const exp = ref(0)
const updateAction = ref(false)
const dialogTitle = ref('')
const accounts = ref([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const form = ref({
  id: 0,
  nickname: '',
  platform: 'pc',
  refreshTokenMethod: 'oauth2',
  username: '',
  password: '',
  master: false,
})

const handleAdd = () => {
  dialogTitle.value = '添加PikPak账号'
  updateAction.value = false
  form.value = {
    id: 0,
    nickname: '',
    platform: 'pc',
    refreshTokenMethod: 'oauth2',
    username: '',
    password: '',
    master: false,
  }
  formVisible.value = true
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新PikPak账号 - ' + data.nickname
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
  axios.delete('/api/pikpak/accounts/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/api/pikpak/accounts/' + form.value.id : '/api/pikpak/accounts'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    if (accounts.value.length === 0) {
      if (store.aListStatus) {
        ElMessage.success('添加成功')
      } else {
        ElMessage.success('添加成功，AList服务重启中。')
        setTimeout(() => router.push('/wait'), 3000)
      }
    } else {
      ElMessage.success('更新成功')
    }
    load()
  })
}

const load = () => {
  axios.get('/api/pikpak/accounts').then(({data}) => {
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
