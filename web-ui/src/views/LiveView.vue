<script setup lang="ts">
import {computed, onMounted, ref} from "vue";
import axios from "axios";
import mpegts from "mpegts.js";
import Hls from "hls.js";
import {onUnmounted} from "@vue/runtime-core";
import {Search, Refresh, CircleCloseFilled, Link} from "@element-plus/icons-vue";
import {ElMessage, type TabsPaneContext} from "element-plus";
import {useRoute, useRouter} from "vue-router";
import {store} from "@/services/store";

const route = useRoute()
const router = useRouter()
const page = ref(1);
const total = ref(0);
const loading = ref(false);
const dialogVisible = ref(false);
const enableLive = ref(false);
const playUrl = ref("");
const playFrom = ref<string[]>([]);
const playUrls = ref<string[]>([]);
const flvPlayer: any = ref();
const hlsPlayer: any = ref();
const categories = ref<Category[]>([]);
const category = ref<Category>({
  type_id: "",
  type_name: "",
  type_flag: 0
});
const types = ref<Movie[]>([]);
const filteredTypes = ref<Movie[]>([]);
const typeKeyword = ref("");
const roomKeyword = ref("");
const rooms = ref<Movie[]>([]);
const filteredRooms = ref<Movie[]>([]);
const type = ref<Movie>({
  vod_id: "",
  vod_name: "",
  vod_actor: "",
  vod_director: "",
  vod_pic: "",
  vod_remarks: "",
  vod_tag: "",
  type_name: "",
  vod_play_from: "",
  vod_play_url: ""
});
const type0 = ref<Movie>({
  vod_id: "",
  vod_name: "",
  vod_actor: "",
  vod_director: "",
  vod_pic: "",
  vod_remarks: "",
  vod_tag: "",
  type_name: "",
  vod_play_from: "",
  vod_play_url: ""
});
const room = ref<Movie>({
  vod_id: "",
  vod_name: "",
  vod_actor: "",
  vod_director: "",
  vod_pic: "",
  vod_remarks: "",
  vod_tag: "",
  type_name: "",
  vod_play_from: "",
  vod_play_url: ""
});
const activeName = ref("");
const activeTab = ref("");
const follows = ref<LiveFollow[]>([]);
const followsLoading = ref(false);
const followLoading = ref(false);
const playGroups = ref<string[]>([]);
const hotMode = ref("folder");
const danmaku = ref<DanmakuConfig>({enabled: true, rows: 0, speed: 1, fontSize: 100, opacity: 100, color: "", showOnline: true});
const platformNames: Record<string, string> = {
  bili: "B站",
  bilibili: "B站",
  cc: "网易",
  douyin: "抖音",
  douyu: "斗鱼",
  huya: "虎牙",
  ks: "快手",
  kuaishou: "快手",
  soop: "SOOP",
  twitch: "Twitch"
};

interface Category {
  type_id: string;
  type_name: string;
  type_flag: number;
}

interface LiveFollow {
  platform: string;
  roomId: string;
  roomName?: string;
  anchorName?: string;
  cover?: string;
  roomUrl?: string;
  live?: boolean | null;
  followedTime?: number;
}

interface DanmakuConfig {
  enabled: boolean;
  rows: number;
  speed: number;
  fontSize: number;
  opacity: number;
  color: string | null;
  showOnline: boolean;
}

interface Movie {
  vod_id: string;
  vod_name: string;
  vod_actor: string;
  vod_director: string;
  vod_pic: string;
  vod_remarks: string;
  vod_tag: string;
  type_name: string;
  vod_play_from: string;
  vod_play_url: string;
}

/**
 * 创建 mpegts 实例;m3u8 地址(Twitch/SOOP 经 /live-proxy 下发)走 hls.js
 */
const initFlv = (ops: { URL: string; elementId: string }) => {
  if (ops.URL.includes(".m3u8") || ops.URL.includes("/live-proxy")) {
    initHls(ops.URL, ops.elementId);
    return;
  }
  if (mpegts.isSupported()) {
    // 根据id名称创建对应的video
    const ele = document.getElementById(ops.elementId);
    flvPlayer.value = mpegts.createPlayer(
      {
        type: "flv", // 指定媒体类型
        isLive: true, // 开启直播（是否为实时流）
        hasAudio: true, // 关闭声音（如果拉过来的视频流中没有音频一定要把这里设置为fasle，否则无法播放）
        cors: true, // 开启跨域访问
        url: ops.URL // 指定流链接（这里是传递过过来的视频流的地址）
      },
      {
        enableWorker: false, //启用分离的线程进行转换（如果不想看到控制台频繁报错把它设置为false，官方的回答是这个属性还不稳定，所以要测试实时视频流的话设置为true控制台经常报错）
        enableStashBuffer: false, //关闭IO隐藏缓冲区（如果需要最小延迟，则设置为false，此项设置针对直播视频流）
        stashInitialSize: 128, //减少首帧等待时长（针对实时视频流）
        lazyLoad: false, //关闭懒加载模式（针对实时视频流）
        lazyLoadMaxDuration: 0.2, //懒加载的最大时长。单位：秒。建议针对直播：调整为200毫秒
        deferLoadAfterSourceOpen: false, //在MediaSource sourceopen事件触发后加载。在Chrome上，在后台打开的标签页可能不会触发sourceopen事件，除非切换到该标签页。
        liveBufferLatencyChasing: true, //追踪内部缓冲区导致的实时流延迟
        liveBufferLatencyMaxLatency: 1.5, //HTMLMediaElement 中可接受的最大缓冲区延迟（以秒为单位）之前使用flv.js发现延时严重，还有延时累加的问题，而mpegts.js对此做了优化，不需要我们自己设置快进追帧了
        liveBufferLatencyMinRemain: 0.3 //HTMLMediaElement 中可接受的最小缓冲区延迟（以秒为单位）
      }
    );
    // mpegts
    flvPlayer.value.attachMediaElement(ele);
    play(flvPlayer.value);
    flvEvent();
  }
};

const initHls = (url: string, elementId: string) => {
  const ele = document.getElementById(elementId) as HTMLVideoElement;
  if (Hls.isSupported()) {
    hlsPlayer.value = new Hls({
      lowLatencyMode: true,
      backBufferLength: 30,
      liveSyncDurationCount: 3
    });
    hlsPlayer.value.loadSource(url);
    hlsPlayer.value.attachMedia(ele);
    hlsPlayer.value.on(Hls.Events.ERROR, (_event: unknown, data: any) => {
      console.log("hls 错误:" + data.type + " " + data.details);
    });
    ele.play();
  } else if (ele.canPlayType("application/vnd.apple.mpegurl")) {
    // Safari 原生 HLS
    ele.src = url;
    ele.play();
  }
};

const play = (flv: any) => {
  flv.load();
  flv.play();
};

// mpegts
const flvEvent = () => {
  // 视频错误信息回调
  flvPlayer.value.on(mpegts.Events.ERROR, (errorType: any, errorDetail: any, errorInfo: any) => {
    console.log(
      "类型:" + JSON.stringify(errorType),
      "报错内容" + errorDetail,
      "报错信息" + errorInfo
    );
  });
};


const destory = () => {
  if (hlsPlayer.value) {
    hlsPlayer.value.destroy();
    hlsPlayer.value = null;
  }
  if (flvPlayer.value) {
    //flvPlayer.value.pause;
    flvPlayer.value.unload();
    flvPlayer.value.detachMediaElement();
    flvPlayer.value.destroy();
    flvPlayer.value = null;
  }
};

const handleClick = (tab: TabsPaneContext) => {
  const index = +(tab.index || "0");
  playUrls.value = playGroups.value[index].split("#");
  loadFlv(playUrls.value[0]);
};

const handleCategoryClick = (tab: TabsPaneContext) => {
  if (tab.props.name === "manage") {
    router.push('/live/manage')
    loadFollows();
    return;
  }
  if (tab.props.name === "danmaku") {
    router.push('/live/danmaku')
    loadDanmakuConfig();
    return;
  }
  if (tab.props.name === "cookies") {
    router.push('/live/cookies')
    loadPlatformCookies();
    return;
  }
  const index = +(tab.index || "0");
  if (index >= categories.value.length) {
    router.push('/live/config')
    loadConfig();
  } else {
    category.value = categories.value[index];
    activeTab.value = category.value.type_id;
    router.push('/live/' + category.value.type_id)
    loadTypes();
  }
};

const loadConfig = () => {
  type.value.vod_id = "";
  type0.value.vod_id = "";
  rooms.value = [];
  filteredRooms.value = [];
  room.value.vod_id = "";
  roomKeyword.value = "";
  types.value = [];
  filteredTypes.value = [];
}

const loadFlv = (url: string) => {
  console.log(url);
  playUrl.value = url;
  destory();
  initFlv({
    URL: url.split("$")[1],
    elementId: "live"
  });
};

const currentRoom = computed(() => {
  const [platform, ...roomIdParts] = room.value.vod_id.split("$");
  return {platform, roomId: roomIdParts.join("$")};
});

const isFollowed = computed(() => currentRoom.value.platform && currentRoom.value.roomId
  && follows.value.some(follow => follow.platform === currentRoom.value.platform && follow.roomId === currentRoom.value.roomId));

const toggleFollow = () => {
  const {platform, roomId} = currentRoom.value;
  if (!platform || !roomId) {
    return;
  }
  const unfollow = isFollowed.value;
  followLoading.value = true;
  const request = unfollow
    ? axios.delete("/api/live/follows", {params: {platform, roomId}})
    : axios.post("/api/live/follows", {platform, roomId});
  request.then(() => {
    ElMessage.success(unfollow ? "已取消关注" : "已关注");
    loadFollows();
  }).catch(() => {
    ElMessage.error("操作失败");
  }).finally(() => {
    followLoading.value = false;
  });
};

const loadFollows = () => {
  followsLoading.value = true;
  axios.get("/api/live/follows").then(({data}) => {
    follows.value = data;
    followsLoading.value = false;
  }).catch(() => {
    followsLoading.value = false;
  });
};

const removeFollow = (row: LiveFollow) => {
  axios.delete("/api/live/follows", {params: {platform: row.platform, roomId: row.roomId}}).then(() => {
    ElMessage.success("已取消关注");
    loadFollows();
  });
};

const followUrl = ref("");
const followUrlLoading = ref(false);

interface PlatformCookie {
  platform: string;
  name: string;
  cookie: string;
  hint: string;
}

const platformCookies = ref<PlatformCookie[]>([]);
const cookieDialogVisible = ref(false);
const cookieEditing = ref<PlatformCookie>({platform: "", name: "", cookie: "", hint: ""});
const cookieSaving = ref(false);
const cookieVerifying = ref(false);

const loadPlatformCookies = () => {
  axios.get("/api/live/cookies").then(({data}) => {
    platformCookies.value = data;
  });
};

const editCookie = (row: PlatformCookie) => {
  cookieEditing.value = {...row};
  cookieDialogVisible.value = true;
};

const saveCookie = () => {
  cookieSaving.value = true;
  axios.put("/api/live/cookies", {platform: cookieEditing.value.platform, cookie: cookieEditing.value.cookie}).then(() => {
    ElMessage.success("已保存,即时生效");
    cookieDialogVisible.value = false;
    loadPlatformCookies();
  }).catch(() => {
    ElMessage.error("保存失败");
  }).finally(() => {
    cookieSaving.value = false;
  });
};

const clearCookie = (row: PlatformCookie) => {
  axios.delete("/api/live/cookies", {params: {platform: row.platform}}).then(() => {
    ElMessage.success("已清除");
    loadPlatformCookies();
  });
};

const verifyCookie = () => {
  cookieVerifying.value = true;
  axios.post("/api/live/cookies/verify", {platform: cookieEditing.value.platform, cookie: cookieEditing.value.cookie}).then(({data}) => {
    if (data.valid) {
      ElMessage.success(data.message);
    } else {
      ElMessage.error(data.message);
    }
  }).catch(() => {
    ElMessage.error("验证请求失败");
  }).finally(() => {
    cookieVerifying.value = false;
  });
};

// 粘贴官方直播间地址直接关注,平台/房间号解析与房间校验都在后端完成
const addFollowByUrl = () => {
  const url = followUrl.value.trim();
  if (!url) {
    ElMessage.warning("请输入直播间地址");
    return;
  }
  followUrlLoading.value = true;
  axios.post("/api/live/follows/url", {url}).then(() => {
    ElMessage.success("关注成功");
    followUrl.value = "";
    loadFollows();
  }).catch((error) => {
    ElMessage.error(error.response?.data?.detail || "添加关注失败");
  }).finally(() => {
    followUrlLoading.value = false;
  });
};

// 弹幕配置已用户级化:各登录用户独立存取,未配置时后端回落全局基线
const loadDanmakuConfig = () => {
  axios.get("/api/live/danmaku-config").then(({data}) => {
    if (data) {
      danmaku.value = {...danmaku.value, ...data};
    }
  });
};

const updateHotMode = () => {
  axios.post("/api/settings", {name: "live_hot_mode", value: hotMode.value}).then(() => {
    ElMessage.success("更新成功");
    // 平台首页层立即按新模式刷新(深入子分类时不打断当前浏览)
    if (!type0.value.vod_id && !type.value.vod_id) {
      loadTypes();
    }
  });
};

const updateDanmakuConfig = () => {
  axios.put("/api/live/danmaku-config", {...danmaku.value, color: danmaku.value.color || ""}).then(() => {
    ElMessage.success("更新成功,播放中最迟 2 秒生效");
  });
};

const openFollowRoom = (row: LiveFollow) => {
  // 未开播房间打开也只有"未开播"占位线路,播放必然黑屏,直接拦截
  if (row.live === false) {
    ElMessage.warning("该直播间未开播");
    return;
  }
  loadRoom(row.platform + "$" + row.roomId);
};

const formatTime = (time?: number) => {
  return time ? new Date(time).toLocaleString() : "";
};

const start = () => {
  loadFlv(playUrls.value[0]);
};

const load = (movie: Movie) => {
  if (movie.vod_tag == "folder") {
    type0.value = Object.assign({}, type.value)
    loadRooms(movie);
  } else {
    loadRoom(movie.vod_id);
  }
};

const loadRoom = (id: string) => {
  loading.value = true;
  axios.get("/live/" + store.token + "?platform=web&ids=" + id).then(({data}) => {
    loading.value = false;
    room.value = data.list[0];
    const sources = room.value.vod_play_from.split("$$$");
    playGroups.value = room.value.vod_play_url.split("$$$").filter(group => !group.split("#").some(url => {
      const [, action] = url.split("$");
      return action === "follow" || action === "unfollow";
    }));
    playFrom.value = sources.slice(0, playGroups.value.length);
    playUrls.value = playGroups.value[0]?.split("#") || [];
    activeName.value = playFrom.value[0];
    dialogVisible.value = true;
    loadFollows();
  });
};

const loadCategories = (id: string) => {
  destory();
  types.value = [];
  type.value.vod_id = "";
  type0.value.vod_id = "";
  rooms.value = [];
  filteredRooms.value = [];
  room.value.vod_id = "";
  typeKeyword.value = "";
  axios.get("/live/" + store.token + '?platform=web').then(({data}) => {
    categories.value = data.class.filter((item: Category) => item.type_id !== "follow");
    if (id === "manage") {
      category.value = categories.value[0];
      activeTab.value = "manage";
      loadFollows();
      return;
    }
    if (id === "danmaku") {
      category.value = categories.value[0];
      activeTab.value = "danmaku";
      loadDanmakuConfig();
      return;
    }
    if (store.admin && id === "cookies") {
      category.value = categories.value[0];
      activeTab.value = "cookies";
      loadPlatformCookies();
      return;
    }
    if (id) {
      category.value = categories.value.find(e => e.type_id == id) || categories.value[0];
    } else {
      category.value = categories.value[0];
    }
    activeTab.value = category.value.type_id;
    loadTypes();
  });
};

const returnHome = () => {
  destory();
  type.value.vod_id = "";
  type0.value.vod_id = "";
  rooms.value = [];
  filteredRooms.value = [];
  room.value.vod_id = "";
};

const returnType = () => {
  destory();
  room.value.vod_id = "";
};

const returnType0 = () => {
  destory();
  room.value.vod_id = "";
  loadRooms(type0.value)
  type0.value.vod_id = "";
};

const loadTypes = () => {
  destory();
  const id = category.value.type_id;
  type.value.vod_id = "";
  type0.value.vod_id = "";
  rooms.value = [];
  filteredRooms.value = [];
  room.value.vod_id = "";
  roomKeyword.value = "";
  axios.get("/live/" + store.token + "?platform=web&t=" + id).then(({data}) => {
    if (id === "follow") {
      // 关注分类直接返回直播间列表(非文件夹)
      types.value = [];
      filteredTypes.value = [];
      rooms.value = data.list;
      filteredRooms.value = data.list;
      total.value = data.pagecount || 1;
    } else {
      types.value = data.list;
      filteredTypes.value = types.value;
    }
  });
};

const filterTypes = () => {
  filteredTypes.value = types.value.filter(e => e.vod_name.toLowerCase().includes(typeKeyword.value.toLowerCase()));
};

const filterRooms = () => {
  filteredRooms.value = rooms.value.filter(e => e.vod_name.toLowerCase().includes(roomKeyword.value.toLowerCase()));
};

const loadRooms = (cate: Movie) => {
  destory();
  room.value.vod_id = "";
  type.value = Object.assign({}, cate);
  reloadRooms(1);
};

const refresh = () => {
  reloadRooms(page.value);
}

const reloadRooms = (value: number) => {
  page.value = value;
  axios.get("/live/" + store.token + "?platform=web&t=" + type.value.vod_id + "&pg=" + value).then(({data}) => {
    rooms.value = data.list;
    filteredRooms.value = data.list;
    total.value = data.pagecount;
  });
};

const updateLive = () => {
  axios.post('/api/settings', {name: 'enable_live', value: enableLive.value}).then(() => {
    ElMessage.success('更新成功')
  })
}

onMounted(async () => {
  if (!store.token) {
    store.token = await axios.get("/api/token").then(({data}) => {
      return data.token ? data.token.split(",")[0] : "-"
    });
  }
  loadCategories(route.params.id as string);
  axios.get("/api/settings/live_hot_mode").then(({data}) => {
    if (data?.value) {
      hotMode.value = data.value;
    }
  });
});

onUnmounted(() => {
  destory();
});
</script>

<template>
  <div class="mainContainer">
    <el-tabs v-model="activeTab" @tab-click="handleCategoryClick">
      <el-tab-pane :label="item.type_name" :name="item.type_id" v-for="item of categories">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>
            <RouterLink :to="'/live/'+category.type_id" @click="returnHome">首页</RouterLink>
          </el-breadcrumb-item>
          <el-breadcrumb-item v-if="type0.vod_id">
            <RouterLink :to="'/live/'+type0.vod_id" @click="returnType0">{{ type0.vod_name }}</RouterLink>
          </el-breadcrumb-item>
          <el-breadcrumb-item v-if="type.vod_id">
            <a href="javascript:void(0);" @click="returnType">{{ type.vod_name }}</a>
          </el-breadcrumb-item>
        </el-breadcrumb>

        <div v-show="!rooms.length">
          <div id="type-filter">
            <el-input
              v-model="typeKeyword"
              style="width: 240px"
              placeholder="筛选"
              @input="filterTypes"
              :prefix-icon="Search"
            />
            <el-select v-model="hotMode" style="width: 140px" @change="updateHotMode">
              <el-option label="热门混排" value="mix"/>
              <el-option label="热门文件夹" value="folder"/>
              <el-option label="仅分类" value="none"/>
            </el-select>
          </div>
          <el-row>
            <el-col :span="5" v-for="type of filteredTypes" class="type">
              <RouterLink :to="'/live/'+type.vod_id" @click="loadRooms(type)" v-if="type.vod_tag=='folder'">
                <div class="card-header">
                  <span>{{ type.vod_name }}</span>
                </div>
                <img :src="type.vod_pic" :alt="type.vod_name">
              </RouterLink>
              <a href="javascript:void(0);" @click="load(type)" v-else>
                <div class="card-header">
                  <span>{{ type.vod_remarks }}： {{ type.vod_name }}</span>
                </div>
                <img :src="type.vod_pic" :alt="type.vod_name">
              </a>
            </el-col>
          </el-row>
        </div>

        <div>
          <div id="pagination">
            <el-button :icon="Refresh" circle @click="refresh"/>
            <el-pagination layout="prev, pager, next" :page-count="total" :current-page="page"
                           @current-change="reloadRooms"/>
            <div v-if="rooms.length&&rooms[0].vod_tag=='folder'">
              <el-input
                v-model="roomKeyword"
                style="width: 240px"
                placeholder="筛选"
                @input="filterRooms"
                :prefix-icon="Search"
              />
            </div>
          </div>
          <el-row>
            <el-col :span="10" v-for="room of filteredRooms" class="room">
              <RouterLink :to="'/live/'+room.vod_id" @click="load(room)" v-if="room.vod_tag=='folder'">
                <div class="card-header">
                  <span>{{ room.vod_remarks }}： {{ room.vod_name }}</span>
                </div>
                <img :src="room.vod_pic" :alt="room.vod_name">
              </RouterLink>
              <a href="javascript:void(0);" @click="load(room)" v-else>
                <div class="card-header">
                  <span>{{ room.vod_remarks }}： {{ room.vod_name }}</span>
                </div>
                <img :src="room.vod_pic" :alt="room.vod_name">
              </a>
            </el-col>
          </el-row>
        </div>
      </el-tab-pane>
      <el-tab-pane label="关注管理" name="manage">
        <div id="follow-toolbar">
          <el-input
            v-model="followUrl"
            class="follow-url-input"
            placeholder="粘贴直播间地址或分享短链,如 https://live.bilibili.com/6"
            clearable
            :prefix-icon="Link"
            @keyup.enter="addFollowByUrl"
          />
          <el-button type="primary" :loading="followUrlLoading" @click="addFollowByUrl">添加关注</el-button>
          <el-button :icon="Refresh" circle @click="loadFollows"/>
          <span v-if="follows.length" class="follow-summary">共 {{ follows.length }} 个关注</span>
        </div>
        <el-table :data="follows" v-loading="followsLoading">
          <el-table-column label="房间" min-width="300">
            <template #default="{row}">
              <div class="follow-room">
                <img v-if="row.cover" :src="row.cover" :alt="row.roomName" referrerpolicy="no-referrer"
                     :class="{offline: row.live === false}" @click="openFollowRoom(row)">
                <div>
                  <a v-if="row.roomUrl" :href="row.roomUrl" target="_blank" rel="noopener noreferrer"
                     class="follow-room-link">{{ row.roomName || row.roomId }}</a>
                  <div v-else>{{ row.roomName || row.roomId }}</div>
                  <div class="follow-anchor">{{ row.anchorName }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="平台" width="100">
            <template #default="{row}">{{ platformNames[row.platform] || row.platform }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{row}">
              <el-tag :type="row.live === true ? 'danger' : row.live === false ? 'info' : 'warning'">
                {{ row.live === true ? '直播中' : row.live === false ? '未开播' : '未知' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="关注时间" width="210">
            <template #default="{row}">{{ formatTime(row.followedTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{row}">
              <el-button size="small" :disabled="row.live === false" @click="openFollowRoom(row)">观看</el-button>
              <el-button size="small" type="danger" @click="removeFollow(row)">取关</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="弹幕管理" name="danmaku">
        <el-form label-width="110px" style="max-width: 620px">
          <el-form-item label="弹幕开关">
            <el-switch
              v-model="danmaku.enabled"
              inline-prompt
              active-text="开启"
              inactive-text="关闭"
              @change="updateDanmakuConfig"
            />
            <span class="danmaku-tip">关闭后播放中约 1 分钟内停止拉取</span>
          </el-form-item>
          <el-form-item label="实时人气值">
            <el-switch
              v-model="danmaku.showOnline"
              inline-prompt
              active-text="显示"
              inactive-text="隐藏"
              @change="updateDanmakuConfig"
            />
            <span class="danmaku-tip">播放画面顶部的实时在线人数</span>
          </el-form-item>
          <el-form-item label="弹幕行数">
            <el-input-number v-model="danmaku.rows" :min="0" :max="8" @change="updateDanmakuConfig"/>
            <span class="danmaku-tip">0 为自动</span>
          </el-form-item>
          <el-form-item label="弹幕速度">
            <el-select v-model="danmaku.speed" style="width: 120px" @change="updateDanmakuConfig">
              <el-option label="慢" :value="0"/>
              <el-option label="正常" :value="1"/>
              <el-option label="快" :value="2"/>
            </el-select>
          </el-form-item>
          <el-form-item label="字体大小">
            <el-slider v-model="danmaku.fontSize" :min="50" :max="200" :step="5" show-input @change="updateDanmakuConfig"/>
          </el-form-item>
          <el-form-item label="不透明度">
            <el-slider v-model="danmaku.opacity" :min="10" :max="100" :step="5" show-input @change="updateDanmakuConfig"/>
          </el-form-item>
          <el-form-item label="弹幕颜色">
            <el-color-picker v-model="danmaku.color" @change="updateDanmakuConfig"/>
            <span class="danmaku-tip">默认跟随平台弹幕原色</span>
          </el-form-item>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="平台Cookie" name="cookies" v-if="store.admin">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
                  title="配置各直播平台的用户 Cookie:抖音风控自愈、SOOP 登录看受限房间、B站登录提高接口配额"
                  description="浏览器打开对应平台并登录,F12 → Network → 任选请求 → Request Headers 里复制完整 Cookie 值粘贴到编辑框"/>
        <el-table :data="platformCookies">
          <el-table-column label="平台" width="100">
            <template #default="{row}">{{ row.name }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="260">
            <template #default="{row}">{{ row.hint }}</template>
          </el-table-column>
          <el-table-column label="Cookie" min-width="320">
            <template #default="{row}">
              <span v-if="row.cookie" class="cookie-preview">{{ row.cookie.length > 60 ? row.cookie.slice(0, 60) + '…' : row.cookie }}</span>
              <span v-else class="cookie-empty">未配置(使用匿名身份)</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{row}">
              <el-button size="small" @click="editCookie(row)">编辑</el-button>
              <el-button size="small" type="danger" :disabled="!row.cookie" @click="clearCookie(row)">清除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
<!--      <el-tab-pane label="配置" name="config">-->
<!--        <el-form label-width="110px">-->
<!--          <el-form-item label="订阅">-->
<!--            <el-switch-->
<!--              v-model="enableLive"-->
<!--              inline-prompt-->
<!--              active-text="开启"-->
<!--              inactive-text="关闭"-->
<!--              @change="updateLive"-->
<!--            />-->
<!--          </el-form-item>-->
<!--        </el-form>-->
<!--      </el-tab-pane>-->
    </el-tabs>

    <el-dialog v-model="cookieDialogVisible" :title="'配置' + cookieEditing.name + ' Cookie'" width="640px">
      <el-alert v-if="cookieEditing.hint" type="info" :closable="false" show-icon style="margin-bottom: 12px"
                :title="cookieEditing.hint"/>
      <el-input v-model="cookieEditing.cookie" type="textarea" :rows="6" placeholder="粘贴浏览器复制的完整 Cookie 值"/>
      <template #footer>
        <el-button @click="cookieDialogVisible = false">取消</el-button>
        <el-button :loading="cookieVerifying" @click="verifyCookie">验证</el-button>
        <el-button type="primary" :loading="cookieSaving" @click="saveCookie">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" :fullscreen="true" :show-close="false" @open="start" @close="destory">
      <template #header="{ close }">
        <div class="my-header">
          <div></div>
          <div class="buttons">
            <el-button @click="close">
              <el-icon class="el-icon--left">
                <CircleCloseFilled/>
              </el-icon>
              关闭
            </el-button>
          </div>
        </div>
      </template>
      <el-row>
        <el-col :span="16">
          <div class="video-container">
            <div>
              <video
                class="video"
                id="live"
                autoplay="true"
                controls>
              </video>
            </div>
          </div>

          <div class="controls">
            <el-tabs v-model="activeName" @tab-click="handleClick">
              <el-tab-pane :label="item" :name="item" v-for="item of playFrom">
                <el-button :type="playUrl==url?'primary':''" v-for="url of playUrls" @click="loadFlv(url)">
                  {{ url.split("$")[0] }}
                </el-button>
              </el-tab-pane>
            </el-tabs>
          </div>
        </el-col>
        <el-col :span="6">
          <el-descriptions :title="room.vod_name">
            <el-descriptions-item label="平台">{{ room.vod_director }}</el-descriptions-item>
            <el-descriptions-item label="类型">{{ room.type_name }}</el-descriptions-item>
            <el-descriptions-item label="主播">{{ room.vod_actor }}</el-descriptions-item>
            <el-descriptions-item label="人气">{{ room.vod_remarks }}</el-descriptions-item>
          </el-descriptions>
          <el-button
            class="follow-button"
            :type="isFollowed ? 'danger' : 'primary'"
            :loading="followLoading"
            @click="toggleFollow"
          >
            {{ isFollowed ? '取消关注' : '关注主播' }}
          </el-button>
        </el-col>
      </el-row>
<!--      <template #footer>-->
<!--      <span class="dialog-footer">-->
<!--        <el-button type="primary" @click="dialogVisible=false">关闭</el-button>-->
<!--      </span>-->
<!--      </template>-->
    </el-dialog>

  </div>
</template>

<style scoped>
.video-container {
  position: relative;
  margin-top: 8px;
}

.video-container:before {
  display: block;
  content: "";
  width: 100%;
  padding-bottom: 56.25%;
}

.video-container > div {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
}

.video-container video {
  width: 100%;
  height: 100%;
}

.my-header {
  display: flex;
  flex-direction: row;
  justify-content: space-between;
  gap: 16px;
}

.controls {
  display: block;
  width: 100%;
  text-align: left;
  margin-left: auto;
  margin-right: auto;
  margin-top: 8px;
  margin-bottom: 10px;
}

.type {
  margin-top: 30px;
  display: block;
  width: 300px;
  height: 300px;
}

.type img {
  width: 100%;
  height: 100%;
}

.room {
  margin-top: 30px;
  display: block;
  width: 640px;
  height: 480px;
}

.room img {
  width: 100%;
  height: 100%;
}

#type-filter {
  display: flex;
  justify-content: flex-end;
}

#follow-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.follow-url-input {
  flex: 1;
  max-width: 480px;
}

.follow-summary {
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.danmaku-tip {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.follow-room {
  display: flex;
  align-items: center;
  gap: 10px;
}

.follow-room img {
  width: 96px;
  height: 54px;
  object-fit: cover;
  border-radius: 4px;
  cursor: pointer;
}

.follow-room img.offline {
  cursor: default;
  filter: grayscale(0.8);
  opacity: 0.6;
}

.follow-room-link {
  color: var(--el-color-primary);
  text-decoration: none;
}

.follow-room-link:hover {
  text-decoration: underline;
}

.follow-anchor {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
}

.cookie-preview {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  word-break: break-all;
}

.cookie-empty {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.follow-button {
  margin-top: 16px;
}

#pagination {
  display: flex;
  justify-content: flex-end;
}
</style>
