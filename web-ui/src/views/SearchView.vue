<template>
  <div class="search">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl+getPath(type)+token+'?wd=' + keyword"
         target="_blank">{{ currentUrl }}{{ getPath(type) }}{{ token }}?wd={{ keyword }}</a>
    </div>

    <div>
      <el-input v-model="keyword" @change="search"/>
      <el-button type="primary" @click="search" :disabled="!keyword">搜索</el-button>
      <el-button type="primary" @click="showDialog" v-if="store.admin">设置</el-button>
    </div>

    <el-form-item label="类型" label-width="140">
      <el-radio-group v-model="type" @change="search" class="ml-4">
        <el-radio label="1" size="large">点播模式</el-radio>
        <el-radio label="" size="large">网盘模式</el-radio>
        <el-radio label="2" size="large">BiliBili</el-radio>
        <el-radio label="4" size="large">Emby</el-radio>
        <el-radio label="5" size="large">Jellyfin</el-radio>
        <el-radio label="6" size="large">鱼佬盘搜</el-radio>
      </el-radio-group>
    </el-form-item>

    <a href="/#/meta">豆瓣电影数据列表</a>
    <span class="divider"></span>
    <a href="/#/tmdb">TMDB电影数据列表</a>

    <el-table v-if="(type==''||type=='1')&&config" :data="config.list" border style="width: 100%">
      <el-table-column prop="vod_name" label="名称" width="300">
        <template #default="scope">
          <a :href="'/#/vod'+scope.row.vod_content" target="_blank">
            {{ scope.row.vod_name }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_content" label="路径">
        <template #default="scope">
          <a :href="'/#/vod'+scope.row.vod_content" target="_blank">
            {{ scope.row.vod_content }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_year" label="年份" width="90"/>
      <el-table-column prop="vod_remarks" label="评分" width="100"/>
    </el-table>

    <el-table v-if="(type=='6')&&config" :data="config.list" border style="width: 100%">
      <el-table-column prop="vod_name" label="名称">
        <template #default="scope">
          <a :href="'/#/vod?link='+scope.row.vod_id" target="_blank">
            {{ scope.row.vod_name }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_id" label="链接" width="350">
        <template #default="scope">
          <a :href="decodeURIComponent(scope.row.vod_id)" target="_blank">
            {{ decodeURIComponent(scope.row.vod_id) }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_remarks" label="类型" width="100"/>
    </el-table>

    <h2 v-if="type!='6'">API返回数据</h2>
    <div class="data" v-if="type!='6'">
      <json-viewer :value="config" expanded copyable show-double-quotes :show-array-index="false" :expand-depth=3>
      </json-viewer>
    </div>

    <el-dialog v-model="dialogVisible" title="配置搜索源">
      <el-form label-width="auto">
        <el-form-item label="搜索文件">
          <el-checkbox-group v-model="form.searchSources">
            <el-checkbox :value="file" name="index" v-for="file in form.files">
              {{ file }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="排除路径">
          <el-input v-model="form.excludedPaths" type="textarea" :rows="15" :placeholder="'多行以/开头的路径'"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="update">更新</el-button>
      </span>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"
import {ElMessage} from "element-plus";
import {useRouter} from "vue-router";
import {store} from "@/services/store";

const router = useRouter()
const token = ref('')
const type = ref('1')
const keyword = ref('')
const config = ref<any>('')
const dialogVisible = ref(false)
const currentUrl = window.location.origin
const form = ref({
  files: [],
  searchSources: [],
  excludedPaths: '',
})

const getPath = (type: string) => {
  if (type == '1') {
    return '/vod1'
  } else if (type == '2') {
    return '/bilibili'
  } else if (type == '3') {
    return '/youtube'
  } else if (type == '4') {
    return '/emby'
  } else if (type == '5') {
    return '/jellyfin'
  } else if (type == '6') {
    return '/pansou'
  } else {
    return '/vod'
  }
}

const search = function () {
  if (!keyword.value) {
    return
  }
  config.value = ''
  axios.get(getPath(type.value) + token.value + '?ac=web&wd=' + keyword.value.trim()).then(({data}) => {
    config.value = data
  })
}

const showDialog = () => {
  axios.get('/api/index-files/settings').then(({data}) => {
    data.excludedPaths = data.excludedPaths.replace(/,/g, '\n')
    form.value = data
    dialogVisible.value = true
  })
}

const update = () => {
  const rule = Object.assign({}, form.value)
  rule.excludedPaths = rule.excludedPaths.replace(/\n/g, ',')
  axios.post('/api/index-files/settings', rule).then(() => {
    ElMessage.success('更新成功')
  })
}

onMounted(() => {
  token.value = store.token
})
</script>

<style scoped>
.description {
  margin-bottom: 12px;
}

.divider {
  margin-left: 24px;
}
</style>
