<template>

  <el-table :data="shares" border style="width: 100%">
    <el-table-column prop="id" label="ID" width="70"/>
    <el-table-column prop="path" label="路径"/>
    <el-table-column prop="url" label="分享连接" width="350">
      <template #default="scope">
        <a :href="getShareLink(scope.row)" target="_blank">https://www.aliyundrive.com/s/{{ scope.row.shareId }}</a>
      </template>
    </el-table-column>
    <el-table-column prop="password" label="密码" width="180"/>
  </el-table>
  <div>
    <el-pagination layout="total, prev, pager, next" :current-page="page" :page-size="size" :total="total"
                   @current-change="load"/>
  </div>

</template>

<script setup lang="ts">
import {onMounted, ref} from "vue";
import axios from "axios";

interface ShareInfo {
  id: string
  path: string
  shareId: string
  folderId: string
  password: string
}

const page = ref(1)
const size = ref(20)
const total = ref(0)
const shares = ref([])

const showDetails = (data: any) => {

}

const getShareLink = (shareInfo: ShareInfo) => {
  let url = 'https://www.aliyundrive.com/s/' + shareInfo.shareId
  if (shareInfo.folderId) {
    url = url + '/folder/' + shareInfo.folderId
  }
  if (shareInfo.password) {
    url = url + '?password=' + shareInfo.password
  }
  return url
}

const load = (value: number) => {
  page.value = value
  axios.get('/shares?page=' + (page.value - 1) + '&size=' + size.value).then(({data}) => {
    shares.value = data.content
    total.value = data.totalElements
  })
}

onMounted(() => {
  load(page.value)
})
</script>

<style scoped>

</style>
