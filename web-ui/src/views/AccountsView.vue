<template>
  <div class="list">
    <h1>阿里账号列表</h1>
    <el-row justify="end">
      <el-button @click="load"> 刷新 </el-button>
      <el-button type="primary" @click="handleAdd"> 添加 </el-button>
    </el-row>
    <div class="space" />

    <el-table :data="accounts" border style="width: 100%">
      <!--      <el-table-column prop="id" label="ID" sortable width="70"/>-->
      <el-table-column prop="nickname" label="昵称" sortable width="180" />
      <el-table-column prop="autoCheckin" label="自动签到" width="90">
        <template #default="scope">
          <el-icon v-if="scope.row.autoCheckin">
            <Check />
          </el-icon>
          <el-icon v-else>
            <Close />
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="checkinDays" label="签到次数" width="90">
        <template #default="scope">
          {{ scope.row.checkinDays }}
          <span class="divider" />
          <el-button link @click="loadTimeline(scope.row.id)">
            <el-icon>
              <Calendar />
            </el-icon>
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="checkinTime" label="上次签到时间">
        <template #default="scope">
          {{ formatTime(scope.row.checkinTime) }}
        </template>
      </el-table-column>
      <el-table-column prop="showMyAli" label="加载我的云盘？" width="150">
        <template #default="scope">
          <el-icon v-if="scope.row.showMyAli">
            <Check />
          </el-icon>
          <el-icon v-else>
            <Close />
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="master" label="主账号？" width="120">
        <template #default="scope">
          <el-icon v-if="scope.row.master">
            <Check />
          </el-icon>
          <el-icon v-else>
            <Close />
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="master" label="开启代理？" width="120">
        <template #default="scope">
          <el-icon v-if="scope.row.useProxy">
            <Check />
          </el-icon>
          <el-icon v-else>
            <Close />
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="concurrency" label="线程数" width="110" />
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="showDetails(scope.row)">
            详情
          </el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle" width="60%">
      <el-form :model="form" label-width="auto">
        <el-form-item label="阿里refresh token" required>
          <el-input
            v-model="form.refreshToken"
            maxlength="128"
            placeholder="长度32位"
            autocomplete="off"
          />
          <a href="https://aliyuntoken.vercel.app/" target="_blank">获取阿里token</a>
          <a href="https://api.oplist.org/" target="_blank" class="hint">获取阿里token</a>
        </el-form-item>
        <el-form-item label="开放refresh token" required>
          <el-input
            v-model="form.openToken"
            type="textarea"
            rows="3"
            minlength="256"
            placeholder="长度280位"
            autocomplete="off"
          />
          <div v-if="tokenUrl.includes('ycyup.cn')" class="hint">
            ycyup:<a
              href="https://ycyup.cn/alipan/authorize"
              title="需要选择ycyup的认证URL"
              target="_blank"
              >获取开放token</a
            >
          </div>
          <div class="hint">
            TV Token:<a href="javascript:void(0)" @click.stop="getQr">扫码获取</a>
          </div>
        </el-form-item>
        <el-form-item v-if="form.openToken" label="加载我的云盘">
          <el-switch
            v-model="form.showMyAli"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
        </el-form-item>
        <el-form-item label="主账号">
          <el-switch v-model="form.master" inline-prompt active-text="是" inactive-text="否" />
          <span class="hint">主账号用来观看分享。</span>
        </el-form-item>
        <el-form-item label="加速代理">
          <el-switch
            v-model="form.useProxy"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
          <span class="hint">服务端多线程加速，网页播放强制开启</span>
        </el-form-item>
        <el-form-item label="代理线程数">
          <el-input-number v-model="form.concurrency" :min="1" :max="16" />
        </el-form-item>
        <el-form-item label="分片大小">
          <el-input-number v-model="form.chunkSize" :min="64" :max="4096" />
        </el-form-item>
        <el-form-item label="自动签到">
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
          <el-button type="primary" @click="handleConfirm">{{
            updateAction ? "更新" : "添加"
          }}</el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除阿里账号" width="30%">
      <p>是否删除阿里账号 - {{ form.id }}</p>
      <p>{{ form.nickname }}</p>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="danger" @click="deleteAccount">删除</el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="阿里账号详情" width="60%">
      <el-form :model="form" label-width="auto">
        <el-form-item v-if="form.accessTokenOpen" prop="accessTokenOpen" label="开放access token">
          <el-input v-model="form.accessTokenOpen" maxlength="128" readonly />
          <div v-if="iat.length > 1 && exp.length > 1">
            <span class="hint">创建时间： {{ formatTime(iat[1]!) }}</span>
            <span class="divider" />
            <span class="hint">过期时间： {{ formatTime(exp[1]!) }}</span>
          </div>
        </el-form-item>
        <el-form-item prop="refreshToken" label="阿里refresh token" required>
          <el-input v-model="form.refreshToken" maxlength="128" placeholder="长度32位" />
          <a href="https://aliyuntoken.vercel.app/" target="_blank">获取阿里token</a>
          <a href="https://api.oplist.org/" target="_blank" class="hint">获取阿里token</a>
          <span class="hint">更新时间： {{ formatTime(form.refreshTokenTime) }}</span>
        </el-form-item>
        <el-form-item prop="openToken" label="开放refresh token" required>
          <el-input
            v-model="form.openToken"
            type="textarea"
            rows="4"
            minlength="256"
            placeholder="长度280位"
          />
          <div v-if="tokenUrl.includes('ycyup.cn')" class="hint">
            ycyup:<a
              href="https://ycyup.cn/alipan/authorize"
              title="需要选择ycyup的认证URL"
              target="_blank"
              >获取开放token</a
            >
          </div>
          <div class="hint">
            TV Token:<a href="javascript:void(0)" @click.stop="getQr">扫码获取</a>
          </div>
          <div v-if="iat.length > 2 && exp.length > 2">
            <span class="hint">创建时间： {{ formatTime(iat[2]!) }}</span>
            <span class="divider" />
            <span class="hint">过期时间： {{ formatTime(exp[2]!) }}</span>
          </div>
        </el-form-item>
        <el-form-item v-if="form.openToken" label="加载我的云盘">
          <el-switch
            v-model="form.showMyAli"
            inline-prompt
            active-text="加载"
            inactive-text="关闭"
          />
        </el-form-item>
        <el-form-item label="主账号">
          <el-switch v-model="form.master" inline-prompt active-text="是" inactive-text="否" />
          <span class="hint">主账号用来观看分享。</span>
        </el-form-item>
        <el-form-item label="自动签到">
          <el-switch
            v-model="form.autoCheckin"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
        </el-form-item>
        <el-form-item label="加速代理">
          <el-switch
            v-model="form.useProxy"
            inline-prompt
            active-text="开启"
            inactive-text="关闭"
          />
          <span class="hint">服务端多线程加速，网页播放强制开启</span>
        </el-form-item>
        <el-form-item label="代理线程数">
          <el-input-number v-model="form.concurrency" :min="1" :max="16" />
        </el-form-item>
        <el-form-item label="分片大小">
          <el-input-number v-model="form.chunkSize" :min="64" :max="4096" />
        </el-form-item>
        <el-form-item v-if="form.checkinTime" label="上次签到时间">
          <el-input :model-value="formatTime(form.checkinTime)" readonly />
          <span v-if="form.checkinDays" class="hint"
            >{{ form.nickname }} 本月签到{{ form.checkinDays }}次</span
          >
        </el-form-item>
        <el-checkbox v-model="forceCheckin" label="强制签到" />
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="detailVisible = false">取消</el-button>
          <el-button type="success" @click="checkin">签到</el-button>
          <el-button type="primary" @click="handleConfirm">更新</el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog v-model="alistVisible" title="更新成功" width="40%">
      <p>需要重启AList服务后才能生效</p>
      <p>是否重启AList服务？</p>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="alistVisible = false">取消</el-button>
          <el-button type="danger" @click="restartAList">重启</el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog v-model="timelineVisible" title="签到日志" width="60%">
      <el-timeline>
        <el-timeline-item
          v-for="(activity, index) in activities"
          :key="index"
          :type="activity.status != 'end' ? 'primary' : ''"
          :hollow="activity.status != 'verification'"
          :timestamp="activity.date"
        >
          {{ activity.name }}
        </el-timeline-item>
      </el-timeline>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="timelineVisible = false">关闭</el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog v-model="qrVisible" title="会员TV Token扫码" width="50%">
      <img alt="qr" :src="'data:image/png;base64,' + base64QrCode" style="width: 500px" />
      <el-form-item>
        <el-button type="primary" @click="checkQr"> 我已经授权 </el-button>
      </el-form-item>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="qrVisible = false">取消</el-button>
        </span>
      </template>
    </el-dialog>

    <div class="divider" />

    <PikPakView />

    <div class="divider" />

    <driver-account-view />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Calendar, Check, Close } from "@element-plus/icons-vue";
import { api } from "@/services/api";
import { ElMessage } from "element-plus";
import { store } from "@/services/store";
import router from "@/router";
import PikPakView from "@/views/PikPakView.vue";
import DriverAccountView from "@/views/DriverAccountView.vue";

const iat = ref([0]);
const exp = ref([0]);
const activities = ref<any[]>([]);
const forceCheckin = ref(false);
const updateAction = ref(false);
const dialogTitle = ref("");
const sid = ref("");
const code = ref("");
const tokenUrl = ref("");
const base64QrCode = ref("");
const accounts = ref([]);
const formVisible = ref(false);
const dialogVisible = ref(false);
const qrVisible = ref(false);
const detailVisible = ref(false);
const alistVisible = ref(false);
const timelineVisible = ref(false);
const form = ref({
  id: 0,
  nickname: "",
  refreshToken: "",
  openToken: "",
  accessToken: "",
  accessTokenOpen: "",
  autoCheckin: true,
  showMyAli: false,
  useProxy: false,
  master: false,
  refreshTokenTime: "",
  openTokenTime: "",
  accessTokenTime: "",
  accessTokenOpenTime: "",
  checkinTime: "",
  checkinDays: 1,
  concurrency: 2,
  chunkSize: 256,
});

const formatTime = (value: string | number) => {
  // JWT iat/exp are in seconds, Date constructor expects milliseconds
  if (typeof value === "number" && value < 10000000000) {
    // Heuristic to check if it's likely seconds
    return new Date(value * 1000).toLocaleString("zh-cn");
  }
  return new Date(value).toLocaleString("zh-cn");
};

const showDetails = (data: any) => {
  form.value = Object.assign({}, data);
  updateAction.value = true;
  if (form.value.accessToken) {
    const parts = form.value.accessToken.split(".");
    if (parts.length > 1) {
      const details = JSON.parse(atob(parts[1]!));
      if (details.iat) {
        iat.value[1] = details.iat;
      }
      if (details.exp) {
        exp.value[1] = details.exp;
      }
    }
  }

  if (form.value.accessTokenOpen) {
    const parts = form.value.accessTokenOpen.split(".");
    if (parts.length > 1) {
      const details = JSON.parse(atob(parts[1]!));
      if (details.iat) {
        iat.value[2] = details.iat;
      }
      if (details.exp) {
        exp.value[2] = details.exp;
      }
    }
  }

  if (form.value.openToken) {
    const parts = form.value.openToken.split(".");
    if (parts.length > 1) {
      const details = JSON.parse(atob(parts[1]!));
      if (details.iat) {
        iat.value[2] = details.iat;
      }
      if (details.exp) {
        exp.value[2] = details.exp;
      }
    }
  }
  detailVisible.value = true;
};

const checkin = () => {
  api
    .post("/api/ali/accounts/" + form.value.id + "/checkin?force=" + forceCheckin.value)
    .then((data) => {
      form.value.checkinTime = data.checkinTime;
      form.value.checkinDays = data.signInCount;
      form.value.nickname = data.nickname;
      forceCheckin.value = false;
      ElMessage.success("签到成功, 本月累计" + data.signInCount + "天");
      load();
    });
};

const getQr = () => {
  api.post("/ali/auth/qr").then((data) => {
    base64QrCode.value = data.img;
    sid.value = data.sid;
    qrVisible.value = true;
  });
};

const checkQr = () => {
  api.get("/ali/auth/qr?sid=" + sid.value).then((data) => {
    code.value = data;
    form.value.accessTokenOpen = data.access_token;
    form.value.openToken = data.refresh_token;
    qrVisible.value = false;
  });
};

const handleAdd = () => {
  dialogTitle.value = "添加阿里账号";
  updateAction.value = false;
  form.value = {
    id: 0,
    nickname: "",
    refreshToken: "",
    openToken: "",
    accessToken: "",
    accessTokenOpen: "",
    autoCheckin: true,
    showMyAli: false,
    useProxy: false,
    master: false,
    refreshTokenTime: "",
    openTokenTime: "",
    accessTokenTime: "",
    accessTokenOpenTime: "",
    checkinTime: "",
    checkinDays: 1,
    concurrency: 2,
    chunkSize: 256,
  };
  formVisible.value = true;
};

const handleDelete = (data: any) => {
  form.value = data;
  dialogVisible.value = true;
};

const deleteAccount = () => {
  dialogVisible.value = false;
  api.delete("/api/ali/accounts/" + form.value.id).then(() => {
    load();
  });
};

const handleCancel = () => {
  formVisible.value = false;
};

const handleConfirm = () => {
  const url = updateAction.value ? "/api/ali/accounts/" + form.value.id : "/api/ali/accounts";
  api.post(url, form.value).then((response) => {
    detailVisible.value = false;
    if (accounts.value.length === 0) {
      if (store.aListStatus) {
        ElMessage.success("添加成功");
      } else {
        ElMessage.success("添加成功，AList服务重启中。");
        setTimeout(() => router.push("/wait"), 3000);
      }
    } else {
      if (response.headers["alist_restart_required"]) {
        ElMessage.success("更新成功，需要重启AList生效");
        alistVisible.value = true;
      } else {
        ElMessage.success("更新成功");
      }
    }
    formVisible.value = false;
    load();
  });
};

const restartAList = () => {
  api.post("/api/alist/restart").then(() => {
    alistVisible.value = false;
    ElMessage.success("AList重启中");
    setTimeout(() => router.push("/wait"), 1000);
  });
};

const loadTimeline = (id: number) => {
  api.get("/api/ali/accounts/" + id + "/checkin").then((data) => {
    activities.value = data;
    timelineVisible.value = true;
  });
};

const load = () => {
  api.get("/api/ali/accounts").then((data) => {
    accounts.value = data;
  });
};

onMounted(() => {
  load();
  api.get("/api/settings/open_token_url").then((data) => {
    tokenUrl.value = data.value;
  });
});
</script>

<style scoped>
.divider {
  margin: 30px 0;
}

.space {
  margin-bottom: 6px;
}

.json pre {
  height: 600px;
  overflow: scroll;
}
</style>
