<template>
  <div class="vod">

    <el-breadcrumb separator="/">
      <el-breadcrumb-item v-for="item in paths">
        <a @click="loadFolder(item.path)">{{ item.text }}</a>
      </el-breadcrumb-item>
    </el-breadcrumb>

    <div class="divider"></div>

    <el-row justify="center">
      <el-col :span="14">
        <el-table v-loading="loading" :data="files" style="width: 100%" @row-click="load">
          <el-table-column prop="vod_name" label="名称">
            <template #default="scope">
              <el-popover placement="left-start" v-if="scope.row.vod_tag!='file'&&scope.row.vod_pic">
                <template #reference>
                  <el-image
                    style="width: 60px; height: 60px"
                    :src="imageUrl(scope.row.vod_pic)"
                    loading="lazy"
                    show-progress
                    fit="cover"
                  />
                </template>
                <template #default>
                  <el-image :src="imageUrl(scope.row.vod_pic)" loading="lazy" show-progress fit="cover"/>
                </template>
              </el-popover>
              {{ scope.row.vod_name }}
            </template>
          </el-table-column>
          <el-table-column label="大小" width="150">
            <template #default="scope">
              {{ scope.row.vod_tag === 'file' ? scope.row.vod_remarks : '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="dbid" label="豆瓣ID" width="120">
            <template #default="scope">
              <a @click.stop :href="'https://movie.douban.com/subject/'+scope.row.dbid" target="_blank"
                 v-if="scope.row.dbid">
                {{ scope.row.dbid }}
              </a>
            </template>
          </el-table-column>
          <el-table-column label="评分" width="90">
            <template #default="scope">
              {{ scope.row.vod_tag === 'folder' ? scope.row.vod_remarks : '' }}
            </template>
          </el-table-column>
          <el-table-column prop="vod_time" label="修改时间" width="165"/>
        </el-table>
        <el-pagination layout="total, prev, pager, next, jumper, sizes"
                       :current-page="page" :page-size="size" :total="total"
                       @current-change="reload" @size-change="handleSizeChange"/>
      </el-col>
    </el-row>

    <el-dialog v-model="dialogVisible" :title="title" :fullscreen="true" @close="stop">
      <div class="video-container">
        <el-row>
          <el-col :span="18">
            <video
              ref="videoPlayer"
              :src="playUrl"
              :autoplay="true"
              @ended="playNextVideo"
              controls>
            </video>
          </el-col>
          <el-col :span="5">
            <div class="playlist" v-if="playlist.length>1">
              <ul>
                <li v-for="(video, index) in playlist" :key="index" @click="playVideo(index)">
                  <el-link :type="currentVideoIndex==index?'primary':''">{{ video.text }}</el-link>
                </li>
              </ul>
            </div>
          </el-col>
        </el-row>

        <el-row>
          <el-col :span="18">
            <div v-if="playlist.length>1">
              <el-button @click="playPrevVideo">
                <el-icon>
                  <ArrowLeftBold/>
                </el-icon>
              </el-button>
              <el-button @click="playNextVideo">
                <el-icon>
                  <ArrowRightBold/>
                </el-icon>
              </el-button>
            </div>
          </el-col>
        </el-row>

        <div class="divider"></div>

        <el-row>
          <el-col :span="18">
            <el-descriptions>
              <el-descriptions-item label="名称">{{ movies[0].vod_name }}</el-descriptions-item>
              <el-descriptions-item label="类型">{{ movies[0].type_name || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="年代">{{ movies[0].vod_year || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="地区">{{ movies[0].vod_area || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="语言">{{ movies[0].vod_lang || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="评分">{{ movies[0].vod_remarks || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="导演">{{ movies[0].vod_director || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="演员">{{ movies[0].vod_actor || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="豆瓣" v-if="movies[0].dbid">
                <a :href="'https://movie.douban.com/subject/'+movies[0].dbid" target="_blank">
                  {{ movies[0].dbid }}
                </a>
              </el-descriptions-item>
            </el-descriptions>
            <p>
              {{ movies[0].vod_content }}
            </p>
          </el-col>
        </el-row>
      </div>

      <template #footer>
        <span class="dialog-footer">
          <el-button type="primary" @click="dialogVisible=false">关闭</el-button>
        </span>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import axios from "axios"
import {ElMessage} from "element-plus";
import type {VodItem} from "@/model/VodItem";
import {useRoute, useRouter} from "vue-router";
import clipBorad from "vue-clipboard3";

let {toClipboard} = clipBorad();

interface Item {
  path: string
  text: string
}

const route = useRoute()
const router = useRouter()
const token = ref('')
const title = ref('');
const playUrl = ref('');
const movies = ref<VodItem[]>([])
const playFrom = ref<string[]>([]);
const playlist = ref<Item[]>([]);
const currentVideoIndex = ref(0)
const loading = ref(false);
const dialogVisible = ref(false);
const page = ref(1)
const size = ref(40)
const total = ref(0)
const files = ref([])
const paths = ref([] as Item[])

const load = (row: any) => {
  if (row.vod_tag === 'folder') {
    loadFolder(row.vod_id)
  } else {
    if (row.vod_name == '播放列表') {
      toClipboard(row.vod_play_url).then(() => {
        ElMessage.success('播放地址复制成功')
      })
    } else {
      currentVideoIndex.value = 0
    }
    {
      loadDetail(row.vod_id)
    }
  }
}

const imageUrl = (url: string) => {
  if (url.endsWith("/folder.png")) {
    return url;
  }
  return '/images?url=' + encodeURIComponent(url)
}

const newImageUrl = (url: string) => {
  if (url.endsWith("/folder.png")) {
    return url;
  }
  return '/images?url=' + encodeURIComponent(url.replace('/s_ratio_poster/', '/m/'))
}

const handleSizeChange = (value: number) => {
  size.value = value
  reload(1)
}

const reload = (value: number) => {
  page.value = value
  loadFiles(paths.value[paths.value.length - 1].path)
}

const loadFolder = (path: string) => {
  router.push(getPath(path))
  page.value = 1
  currentVideoIndex.value = 0
  loadFiles(path)
}

const loadFiles = (path: string) => {
  const id = extractPaths(path)
  loading.value = true
  files.value = []
  axios.get('/vod' + token.value + '?ac=web&pg=' + page.value + '&size=' + size.value + '&t=' + id).then(({data}) => {
    files.value = data.list
    total.value = data.total
    loading.value = false
  }, () => {
    loading.value = false
  })
}

const getPath = (id: string) => {
  return '/vod' + decodeURIComponent(id).replace('1$', '').split('$')[0]
}

const extractPaths = (id: string) => {
  const path = decodeURIComponent(id).replace('1$', '').split('$')[0]
  if (path == '/') {
    paths.value = [{text: '🏠首页', path: '/'}]
  } else {
    const array = path.split('/')
    const items: Item[] = []
    for (let index = 0; index < array.length; ++index) {
      const path = array.slice(0, index + 1).join('/')
      const text = array[index]
      items.push({text: text ? text : '🏠首页', path: path ? path : '/'})
    }
    paths.value = items
  }
  if (id.startsWith('1$')) {
    return id
  }
  return '1$' + path + '$1'
}

const loadDetail = (id: string) => {
  axios.get('/vod' + token.value + '?ac=web&ids=' + id).then(({data}) => {
    movies.value = data.list
    playFrom.value = movies.value[0].vod_play_from.split("$$$");
    playlist.value = movies.value[0].vod_play_url.split("$$$")[0].split("#").map(e => {
      let u = e.split('$')
      if (u.length == 1) {
        return {
          path: e,
          text: movies.value[0].vod_name
        }
      } else {
        return {
          path: u[1],
          text: u[0]
        }
      }
    })
    playUrl.value = getPlayUrl(currentVideoIndex.value)
    dialogVisible.value = true
  })
}

const stop = () => {
  playUrl.value = ""
}

const getPlayUrl = (index: number) => {
  let url = playlist.value[index].path
  title.value = playlist.value[index].text
  console.log('play', index, title, url)
  return url
}

const playNextVideo = () => {
  currentVideoIndex.value++;
  if (currentVideoIndex.value >= playlist.value.length) {
    return
  }
  playUrl.value = getPlayUrl(currentVideoIndex.value);
}

const playPrevVideo = () => {
  currentVideoIndex.value--;
  if (currentVideoIndex.value < 0) {
    return
  }
  playUrl.value = getPlayUrl(currentVideoIndex.value);
}

const playVideo = (index: number) => {
  currentVideoIndex.value = index;
  playUrl.value = getPlayUrl(currentVideoIndex.value);
}

onMounted(async () => {
  axios.get('/api/token').then(({data}) => {
    token.value = data ? '/' + (data + '').split(',')[0] : ''
    if (Array.isArray(route.params.path)) {
      const path = route.params.path.join('/')
      loadFiles('/' + path)
    } else {
      loadFiles('/')
    }
  })
})
</script>

<style scoped>
video {
  width: 100%;
}

.divider {
  margin: 15px 0;
}

.playlist {
  max-height: 720px;
  overflow-y: auto;
}
</style>
