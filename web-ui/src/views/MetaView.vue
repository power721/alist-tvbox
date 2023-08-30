<template>
  <div class="files">
    <h1>电影数据列表</h1>
    <el-row justify="end">
      <el-input v-model="keyword" @change="search" class="search" autocomplete="off"/>
      <el-button type="primary" @click="search" :disabled="!keyword">
        搜索
      </el-button>
      <el-button @click="refresh">刷新</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="files" border style="width: 100%">
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="name" label="电影名称" width="250"/>
      <el-table-column prop="path" label="路径"/>
      <el-table-column prop="year" label="年份" width="100"/>
      <el-table-column prop="score" label="评分" width="100"/>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div>
      <el-pagination layout="total, prev, pager, next" :current-page="page" :page-size="size" :total="total"
                     @current-change="load"/>
    </div>

    <el-dialog v-model="dialogVisible" title="删除电影数据" width="30%">
      <p>是否删除电影数据 - {{ form.name }}</p>
      <p>{{ form.path }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSub">删除</el-button>
      </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"

const keyword = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const files = ref([])
const dialogVisible = ref(false)
const fullscreen = ref(false)
const form = ref({
  id: 0,
  name: '',
  path: '',
  year: 0,
  score: 0
})

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSub = () => {
  dialogVisible.value = false
  axios.delete('/meta/' + form.value.id).then(() => {
    refresh()
  })
}

const search = () => {
  load(1)
}

const refresh = () => {
  load(page.value)
}

const load = (value: number) => {
  page.value = value
  axios.get('/meta?page=' + (page.value - 1) + '&size=' + size.value + '&q=' + keyword.value).then(({data}) => {
    files.value = data.content
    total.value = data.totalElements
  })
}

onMounted(() => {
  refresh()
})
</script>

<style scoped>
.search {
  width: 350px;
}

.space {
  margin-bottom: 6px;
}
</style>
