<template>
  <div class="vod">

    <el-row justify="space-between">
      <el-col :span="20">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="item in paths">
            <a @click="loadFolder(item.path)">{{ item.text }}</a>
          </el-breadcrumb-item>
        </el-breadcrumb>
      </el-col>

      <el-col :span="2">
        <el-button :icon="Film" circle @click="loadHistory"></el-button>
      </el-col>
    </el-row>

    <div class="divider"></div>

    <el-row justify="center">
      <el-col :span="14">
        <el-table v-loading="loading" :data="files" style="width: 100%" @row-click="load">
          <el-table-column prop="vod_name" label="名称">
            <template #default="scope">
              <el-popover :width="300" placement="left-start" v-if="scope.row.vod_tag=='folder'&&scope.row.vod_pic">
                <template #reference>
                  📺
                </template>
                <template #default>
                  <el-image :src="imageUrl(scope.row.vod_pic)" loading="lazy" show-progress fit="cover"/>
                </template>
              </el-popover>
              <span v-else-if="scope.row.vod_tag=='folder'">📂</span>
              <span v-else-if="scope.row.vod_id.endsWith('playlist$1')">▶️</span>
              <span v-else>🎬</span>
              {{ scope.row.vod_name }}
            </template>
          </el-table-column>
          <el-table-column label="大小" width="120">
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

    <el-dialog v-model="dialogVisible" :title="title" :fullscreen="true" @opened="start" @close="stop">
      <div class="video-container">
        <el-row>
          <el-col :span="18">
            <video
              ref="videoPlayer"
              :src="playUrl"
              :autoplay="true"
              @ended="playNextVideo"
              @play="updatePlayState"
              @pause="updatePlayState"
              @volumechange="updateMuteState"
              controls>
            </video>
          </el-col>
          <el-col :span="5">
            <div v-if="playlist.length>1">
              <div style="margin-left: 30px; margin-bottom: 10px;">
                第{{ currentVideoIndex + 1 }}集 / 总共{{ playlist.length }}集
              </div>
              <el-scrollbar ref="scrollbarRef" height="720px">
                <ul>
                  <li v-for="(video, index) in playlist" :key="index" @click="playVideo(index)">
                    <el-link type="primary" v-if="currentVideoIndex==index">{{ video.text }}</el-link>
                    <el-link v-else>{{ video.text }}</el-link>
                  </li>
                </ul>
              </el-scrollbar>
            </div>
          </el-col>
        </el-row>

        <el-row>
          <el-col :span="18">
            <div>
              <el-button-group>
                <el-button @click="play" v-if="!playing">播放</el-button>
                <el-button @click="pause" v-if="playing">暂停</el-button>
                <el-button @click="toggleMute">{{ isMuted ? '取消静音' : '静音' }}</el-button>
                <el-button @click="toggleFullscreen">全屏</el-button>
                <el-button @click="skipBackward">-15</el-button>
                <el-button @click="skipForward">+15</el-button>
                <el-button @click="playPrevVideo" v-if="playlist.length>1">上集</el-button>
                <el-button @click="playNextVideo" v-if="playlist.length>1">下集</el-button>
                <el-popover placement="right-start">
                  <template #reference>
                    <el-button :icon="QuestionFilled"/>
                  </template>
                  <template #default>
                    <div>
                      <div>播放： 空格键</div>
                      <div>全屏： 回车键</div>
                      <div>退出： Esc</div>
                      <div>静音： m</div>
                      <div>后退15秒： ←</div>
                      <div>前进15秒： →</div>
                      <div v-if="playlist.length>1">上集： ↑</div>
                      <div v-if="playlist.length>1">下集： ↓</div>
                    </div>
                  </template>
                </el-popover>
              </el-button-group>
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
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
// @ts-nocheck
import {onMounted, ref} from 'vue'
import axios from "axios"
import {ElMessage, type ScrollbarInstance} from "element-plus";
import type {VodItem} from "@/model/VodItem";
import {useRoute, useRouter} from "vue-router";
import clipBorad from "vue-clipboard3";
import {onUnmounted} from "@vue/runtime-core";
import {Film, QuestionFilled} from "@element-plus/icons-vue";

let {toClipboard} = clipBorad();

interface Item {
  path: string
  text: string
}

let timer = 0
const route = useRoute()
const router = useRouter()
const videoPlayer = ref(null)
const scrollbarRef = ref<ScrollbarInstance>()
const token = ref('')
const title = ref('')
const playUrl = ref('')
const movies = ref<VodItem[]>([])
const playFrom = ref<string[]>([])
const playlist = ref<Item[]>([])
const currentVideoIndex = ref(0)
const currentTime = ref(0)
const loading = ref(false)
const playing = ref(false)
const isMuted = ref(false)
const isFullscreen = ref(false)
const dialogVisible = ref(false)
const page = ref(1)
const size = ref(40)
const total = ref(0)
const files = ref<VodItem[]>([])
const paths = ref<Item[]>([])

const load = (row: any) => {
  if (row.vod_tag === 'folder') {
    loadFolder(row.vod_id)
  } else {
    if (row.vod_name == '播放列表') {
      toClipboard(row.vod_play_url).then(() => {
        ElMessage.success('播放地址复制成功')
      })
    }
    loadDetail(row.vod_id)
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
  if (path == '/~history') {
    return
  }
  router.push('/vod' + getPath(path).replace('\t', '%09'))
  page.value = 1
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
  return decodeURIComponent(id).replace('1$', '').replace('$1', '')
}

const extractPaths = (id: string) => {
  const path = getPath(id)
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
  return '1$' + encodeURIComponent(path) + '$1'
}

const loadDetail = (id: string) => {
  axios.get('/vod' + token.value + '?ac=web&ids=' + id).then(({data}) => {
    movies.value = data.list
    movies.value[0].vod_id = id
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
    getHistory(id)
    getPlayUrl()
    dialogVisible.value = true
  })
}

const handleKeyDown = (event: KeyboardEvent) => {
  if (!dialogVisible.value) {
    if (event.code === 'Space' && files.value.length > 0 && files.value[0].vod_tag === 'file') {
      event.preventDefault()
      loadDetail(files.value[0].vod_id)
    } else if (event.code === 'Escape' && paths.value.length > 1) {
      loadFolder(paths.value[paths.value.length - 2].path)
    }
    return
  }
  if (event.code === 'Space') {
    event.preventDefault()
    togglePlay()
  } else if (event.code === 'ArrowRight') {
    event.preventDefault()
    skipForward()
  } else if (event.code === 'ArrowLeft') {
    event.preventDefault()
    skipBackward()
  } else if (event.code === 'ArrowUp') {
    event.preventDefault()
    playPrevVideo()
  } else if (event.code === 'ArrowDown') {
    event.preventDefault()
    playNextVideo()
  } else if (event.code === 'Enter') {
    event.preventDefault()
    toggleFullscreen()
  } else if (event.code === 'KeyM') {
    event.preventDefault()
    toggleMute()
  }
}

const togglePlay = () => {
  if (videoPlayer.value) {
    if (playing.value) {
      pause()
    } else {
      play()
    }
  }
}

const start = () => {
  if (videoPlayer.value) {
    videoPlayer.value.currentTime = currentTime.value
    if (scrollbarRef.value) {
      scrollbarRef.value.setScrollTop(currentVideoIndex.value * 20)
    }
    play()
  }
}

const stop = () => {
  if (videoPlayer.value) {
    pause()
    saveHistory()
  }
}

const play = () => {
  if (videoPlayer.value) {
    const res = videoPlayer.value.play();
    if (res) {
      res.then(_ => {
        playing.value = true
        saveHistory()
      }).catch(e => {
        console.error(e)
      })
    }
  }
}

const pause = () => {
  if (videoPlayer.value) {
    const res = videoPlayer.value.pause();
    if (res) {
      res.then(_ => {
        playing.value = false
        saveHistory()
      }).catch(e => {
        console.error(e)
      })
    }
  }
}

const skipForward = () => {
  if (videoPlayer.value) {
    videoPlayer.value.currentTime += 15;
    play()
  }
}

const skipBackward = () => {
  if (videoPlayer.value) {
    videoPlayer.value.currentTime -= 15;
    play()
  }
}

const toggleMute = () => {
  if (videoPlayer.value) {
    videoPlayer.value.muted = !videoPlayer.value.muted;
  }
}

const toggleFullscreen = () => {
  if (videoPlayer.value) {
    if (!isFullscreen.value) {
      enterFullscreen(videoPlayer.value);
    } else {
      exitFullscreen();
    }
  }
}

const enterFullscreen = (element) => {
  if (element.requestFullscreen) {
    element.requestFullscreen();
  } else if (element.mozRequestFullScreen) { // Firefox
    element.mozRequestFullScreen();
  } else if (element.webkitRequestFullscreen) { // Chrome, Safari
    element.webkitRequestFullscreen();
  } else if (element.msRequestFullscreen) { // IE/Edge
    element.msRequestFullscreen();
  }
}

const exitFullscreen = () => {
  if (document.exitFullscreen) {
    document.exitFullscreen();
  } else if (document.mozCancelFullScreen) {
    document.mozCancelFullScreen();
  } else if (document.webkitExitFullscreen) {
    document.webkitExitFullscreen();
  } else if (document.msExitFullscreen) {
    document.msExitFullscreen();
  }
};

const handleFullscreenChange = () => {
  isFullscreen.value = document.fullscreenElement === videoPlayer.value;
};

const updatePlayState = () => {
  if (videoPlayer.value) {
    playing.value = !videoPlayer.value.paused;
  }
}

const updateMuteState = () => {
  if (videoPlayer.value) {
    isMuted.value = videoPlayer.value.muted;
  }
}

const getPlayUrl = () => {
  saveHistory()
  const index = currentVideoIndex.value
  playUrl.value = playlist.value[index].path
  title.value = playlist.value[index].text
}

const save = () => {
  if (playing.value) {
    saveHistory()
  }
}

const saveHistory = () => {
  if (!videoPlayer.value || !dialogVisible.value) {
    return
  }
  const movie = movies.value[0]
  const id = movie.vod_id
  const name = movie.vod_name
  const index = currentVideoIndex.value
  const items = JSON.parse(localStorage.getItem('history') || '[]')
  for (let item of items) {
    if (item.id === id) {
      item.i = index
      item.c = videoPlayer.value.currentTime
      item.t = new Date().getTime()
      localStorage.setItem('history', JSON.stringify(items))
      return
    }
  }
  items.push({id: id, n: name, i: index, c: videoPlayer.value.currentTime, t: new Date().getTime()})
  const sorted = items.sort((a, b) => b.t - a.t).slice(0, 40);
  localStorage.setItem('history', JSON.stringify(sorted))
}

const getHistory = (id: string) => {
  const items = JSON.parse(localStorage.getItem('history') || '[]')
  for (let item of items) {
    if (item.id === id) {
      currentVideoIndex.value = item.i
      currentTime.value = item.c
      return
    }
  }
  currentTime.value = 0
  currentVideoIndex.value = 0
}

const formatDate = (timestamp: number): string => {
  const date: Date = new Date(timestamp);
  const year: number = date.getFullYear();
  const month: string = String(date.getMonth() + 1).padStart(2, '0');
  const day: string = String(date.getDate()).padStart(2, '0');
  const hours: string = String(date.getHours()).padStart(2, '0');
  const minutes: string = String(date.getMinutes()).padStart(2, '0');
  const seconds: string = String(date.getSeconds()).padStart(2, '0');
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`;
}

const loadHistory = () => {
  const items = JSON.parse(localStorage.getItem('history') || '[]')
  files.value = items.sort((a, b) => b.t - a.t).map(e => {
    return {
      vod_id: e.id,
      vod_name: e.n,
      vod_tag: 'file',
      vod_time: formatDate(e.t)
    }
  })
  total.value = files.value.length
  paths.value = [{text: '🏠首页', path: '/'}, {text: '播放记录', path: '/~history'}]
}

const playNextVideo = () => {
  if (currentVideoIndex.value + 1 == playlist.value.length) {
    return
  }
  currentVideoIndex.value++;
  getPlayUrl();
}

const playPrevVideo = () => {
  if (currentVideoIndex.value < 1) {
    return
  }
  currentVideoIndex.value--;
  getPlayUrl();
}

const playVideo = (index: number) => {
  currentVideoIndex.value = index;
  getPlayUrl();
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
  timer = setInterval(save, 5000)
  window.addEventListener('keydown', handleKeyDown);
  document.addEventListener('fullscreenchange', handleFullscreenChange);
})

onUnmounted(() => {
  clearInterval(timer)
  window.removeEventListener('keydown', handleKeyDown);
  document.removeEventListener('fullscreenchange', handleFullscreenChange);
})
</script>

<style scoped>
video {
  width: 100%;
}

.divider {
  margin: 15px 0;
}
</style>
