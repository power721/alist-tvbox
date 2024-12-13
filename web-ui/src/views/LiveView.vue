<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import axios from "axios";
import mpegts from "mpegts.js";
import { onUnmounted } from "@vue/runtime-core";
import { Search, Refresh } from "@element-plus/icons-vue";
import type { TabsPaneContext } from "element-plus";

const page = ref(1);
const total = ref(0);
const loading = ref(false);
const dialogVisible = ref(false);
const id = ref("");
const token = ref("");
const playUrl = ref("");
const playFrom = ref<string[]>([]);
const playUrls = ref<string[]>([]);
const flvPlayer: any = ref();
const categories = ref<Category[]>([]);
const category = ref<Category>({
  type_id: "",
  type_name: "",
  type_flag: 0
});
const types = ref<Movie[]>([]);
const filteredTypes = ref<Movie[]>([]);
const typeKeyword = ref("");
const rooms = ref<Movie[]>([]);
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

interface Category {
  type_id: string;
  type_name: string;
  type_flag: number;
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
 * 创建 mpegts 实例
 */
const initFlv = (ops: { URL: string; elementId: string }) => {
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
  playUrls.value = room.value.vod_play_url.split("$$$")[index].split("#");
  loadFlv(playUrls.value[0]);
};

const handleCategoryClick = (tab: TabsPaneContext) => {
  const index = +(tab.index || "0");
  category.value = categories.value[index];
  loadTypes();
};

const loadFlv = (url: string) => {
  console.log(url);
  playUrl.value = url;
  destory();
  initFlv({
    URL: url.split("$")[1],
    elementId: "live"
  });
};

const start = () => {
  loadFlv(playUrls.value[0]);
};

const load = (movie: Movie) => {
  if (movie.vod_tag == "folder") {
    type0.value = Object.assign({}, type.value)
    loadRooms(movie);
  } else {
    type0.value.vod_id = ""
    loadRoom(movie.vod_id);
  }
};

const loadRoom = (id: string) => {
  loading.value = true;
  axios.get("/live" + token.value + "?ids=" + id).then(({ data }) => {
    loading.value = false;
    room.value = data.list[0];
    playFrom.value = room.value.vod_play_from.split("$$$");
    playUrls.value = room.value.vod_play_url.split("$$$")[0].split("#");
    activeName.value = playFrom.value[0];
    dialogVisible.value = true;
  });
};

const loadCategories = () => {
  destory();
  types.value = [];
  type.value.vod_id = "";
  rooms.value = [];
  room.value.vod_id = "";
  typeKeyword.value = "";
  axios.get("/live" + token.value).then(({ data }) => {
    categories.value = data.class;
    category.value = categories.value[0];
    loadTypes();
  });
};

const returnHome = () => {
  destory();
  type.value.vod_id = "";
  type0.value.vod_id = "";
  rooms.value = [];
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
  rooms.value = [];
  room.value.vod_id = "";
  axios.get("/live" + token.value + "?t=" + id).then(({ data }) => {
    types.value = data.list;
    filteredTypes.value = types.value;
  });
};

const filterTypes = () => {
  filteredTypes.value = types.value.filter(e => e.vod_name.toLowerCase().includes(typeKeyword.value.toLowerCase()));
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
  axios.get("/live" + token.value + "?t=" + type.value.vod_id + "&pg=" + value).then(({ data }) => {
    rooms.value = data.list;
    total.value = data.pagecount;
  });
};

onMounted(async () => {
  token.value = await axios.get("/api/token").then(({ data }) => {
    return data ? "/" + (data + "").split(",")[0] : "";
  });
  loadCategories();
});

onUnmounted(() => {
  destory();
});
</script>

<template>
  <div class="mainContainer">
    <el-tabs v-model="category.type_id" @tab-click="handleCategoryClick">
      <el-tab-pane :label="item.type_name" :name="item.type_id" v-for="item of categories">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>
            <a href="javascript:void(0);" @click="returnHome">首页</a>
          </el-breadcrumb-item>
          <el-breadcrumb-item v-if="type0.vod_id">
            <a href="javascript:void(0);" @click="returnType0">{{ type0.vod_name }}</a>
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
              @change="filterTypes"
              :prefix-icon="Search"
            />
          </div>
          <el-row>
            <el-col :span="5" v-for="type of filteredTypes" class="type">
              <a href="javascript:void(0);" @click="loadRooms(type)">
                <div class="card-header">
                  <span>{{ type.vod_name }}</span>
                </div>
                <img :src="type.vod_pic" :alt="type.vod_name">
              </a>
            </el-col>
          </el-row>
        </div>

        <div>
          <div id="pagination">
            <el-button :icon="Refresh" circle @click="refresh" />
            <el-pagination layout="prev, pager, next" :page-count="total" :current-page="page"
                           @current-change="reloadRooms" />
          </div>
          <el-row>
            <el-col :span="10" v-for="room of rooms" class="room">
              <a href="javascript:void(0);" @click="load(room)">
                <div class="card-header">
                  <span>{{ room.vod_remarks }}： {{ room.vod_name }}</span>
                </div>
                <img :src="room.vod_pic" :alt="room.vod_name">
              </a>
            </el-col>
          </el-row>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" :fullscreen="true" @open="start" @close="destory">
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
        </el-col>
      </el-row>
      <template #footer>
      <span class="dialog-footer">
        <el-button type="primary" @click="dialogVisible=false">关闭</el-button>
      </span>
      </template>
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

#pagination {
  display: flex;
  justify-content: flex-end;
}
</style>
