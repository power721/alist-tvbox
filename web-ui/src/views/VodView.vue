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
              <el-popover :width="300" placement="left-start" v-if="scope.row.vod_tag!='file'&&scope.row.vod_pic">
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

    <el-dialog v-model="dialogVisible" :title="title" :fullscreen="true" @opened="play" @close="pause">
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
              <el-scrollbar height="720px">
                <ul>
                  <li v-for="(video, index) in playlist" :key="index" @click="playVideo(index)">
                    <el-link :type="currentVideoIndex==index?'primary':''">{{ video.text }}</el-link>
                  </li>
                </ul>
              </el-scrollbar>
              <div style="margin-left: 30px; margin-top: 12px;">
                第{{ currentVideoIndex + 1 }}集 / 总共{{ playlist.length }}集
                <el-tooltip content="上一集" placement="top" effect="light">
                  <el-button link @click="playPrevVideo">
                    <el-icon>
                      <ArrowLeftBold/>
                    </el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip content="下一集" placement="top" effect="light">
                  <el-button link @click="playNextVideo">
                    <el-icon>
                      <ArrowRightBold/>
                    </el-icon>
                  </el-button>
                </el-tooltip>
              </div>
            </div>
          </el-col>
        </el-row>

        <el-row>
          <el-col :span="18">
            <div>
              <el-button-group>
                <el-button @click="playPrevVideo" v-if="playlist.length>1">上一集</el-button>
                <el-button @click="play" v-if="!playing">播放</el-button>
                <el-button @click="pause" v-if="playing">暂停</el-button>
                <el-button @click="toggleMute">{{ isMuted ? '取消静音' : '静音' }}</el-button>
                <el-button @click="toggleFullscreen">全屏</el-button>
                <el-button @click="skipBackward">-15</el-button>
                <el-button @click="skipForward">+15</el-button>
                <el-button @click="playNextVideo" v-if="playlist.length>1">下一集</el-button>
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
import {onMounted, ref} from 'vue'
import axios from "axios"
import {ElMessage} from "element-plus";
import type {VodItem} from "@/model/VodItem";
import {useRoute, useRouter} from "vue-router";
import clipBorad from "vue-clipboard3";
import {onUnmounted} from "@vue/runtime-core";

let {toClipboard} = clipBorad();

interface Item {
  path: string
  text: string
}

const route = useRoute()
const router = useRouter()
const videoPlayer = ref(null)
const token = ref('')
const title = ref('')
const playUrl = ref('')
const movies = ref<VodItem[]>([])
const playFrom = ref<string[]>([])
const playlist = ref<Item[]>([])
const currentVideoIndex = ref(0)
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
    } else {
      currentVideoIndex.value = 0
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

const handleKeyDown = (event) => {
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

const play = () => {
  if (videoPlayer.value) {
    videoPlayer.value.play();
    playing.value = true
  }
}

const pause = () => {
  if (videoPlayer.value) {
    videoPlayer.value.pause();
    playing.value = false
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

const getPlayUrl = (index: number) => {
  let url = playlist.value[index].path
  title.value = playlist.value[index].text
  return url
}

const playNextVideo = () => {
  if (currentVideoIndex.value + 1 == playlist.value.length) {
    return
  }
  currentVideoIndex.value++;
  playUrl.value = getPlayUrl(currentVideoIndex.value);
}

const playPrevVideo = () => {
  if (currentVideoIndex.value < 1) {
    return
  }
  currentVideoIndex.value--;
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
  window.addEventListener('keydown', handleKeyDown);
  document.addEventListener('fullscreenchange', handleFullscreenChange);
})

onUnmounted(() => {
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
