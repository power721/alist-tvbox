<script setup lang="ts">
// @ts-nocheck
import { RouterView, useRoute, useRouter } from "vue-router";
import accountService from "@/services/account.service";
import { computed, onMounted, ref } from "vue";
import { api } from "@/services/api";
import { store } from "@/services/store";
import zhCn from "element-plus/dist/locale/zh-cn.mjs";
import { useDark, useLocalStorage } from "@vueuse/core";

const account = accountService.account;
const route = useRoute();
const router = useRouter();
const show = ref(true);
const full = useLocalStorage("full_view", false);
const mounted = ref(false);
// notification2: 'true' means hidden (legacy)
const notificationHidden = useLocalStorage("notification2", false);
const showNotification = computed(() => !notificationHidden.value);

// useDark automatically handles the .dark class on the html element
const isDark = useDark();

const logout = () => {
  accountService.logout();
  router.push("/login");
};

const close = () => {
  notificationHidden.value = true;
};

const onModeChange = (value: boolean) => {
  full.value = value;
};

onMounted(() => {
  // Logic simplified: notificationHidden handles persistency.

  api.get("/api/token").then((data) => {
    store.token = data.token ? data.token.split(",")[0] : "-";
    store.role = data.role;
    store.admin = data.role === "ADMIN";
  });

  api.get("/api/profiles").then((data) => {
    store.xiaoya = data.includes("xiaoya");
    store.docker = data.includes("docker");
    store.standalone = data.includes("standalone");
    store.hostmode = data.includes("host");
    api.get("/api/settings/install_mode").then((data) => {
      store.installMode = data.value;
    });
    mounted.value = true;
    if (show.value) {
      api.get("/api/alist/status").then((data) => {
        store.aListStatus = data;
        show.value = show.value && data != 1;
        if (data === 1) {
          router.push("/wait?redirect=" + route.path);
        } else if (!store.admin && route.path === "/") {
          router.push("/vod");
        }
      });
    }
  });
});
</script>

<template>
  <div class="common-layout">
    <el-container>
      <el-header>
        <el-menu mode="horizontal" :ellipsis="false" :router="true">
          <el-menu-item v-if="store.admin" index="/"> 首页 </el-menu-item>
          <el-menu-item v-if="account.authenticated && store.admin" index="/sites">
            站点
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && show && store.admin" index="/accounts">
            账号
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && full && store.admin" index="/bilibili">
            BiliBili
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && store.admin" index="/subscriptions">
            订阅
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && show && full && store.admin" index="/shares">
            资源
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && store.admin" index="/config">
            配置
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && full && store.admin" index="/acl">
            ACL
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && show && full && store.admin" index="/index">
            索引
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && store.admin" index="/logs">
            日志
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && show && full && store.admin" index="/files">
            文件
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && show && full && store.admin" index="/alias">
            别名
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && show && full && store.admin" index="/users">
            用户
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && (full || !store.admin)" index="/search">
            搜索
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && show && (full || !store.admin)" index="/vod">
            播放
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && (full || !store.admin)" index="/live">
            直播
          </el-menu-item>
          <el-menu-item v-if="account.authenticated && store.admin" index="/about">
            关于
          </el-menu-item>
          <div class="flex-grow" />
          <span v-if="account.authenticated && store.admin" id="mode">
            <el-switch
              v-model="full"
              inline-prompt
              active-text="高级模式"
              inactive-text="简单模式"
              style="
                --el-switch-on-color: #13ce66;
                --el-switch-off-color: #409eff;
                margin-top: -24px;
              "
              @change="onModeChange"
            />
          </span>
          <el-sub-menu v-if="account.authenticated">
            <template #title>
              {{ account.username }}
            </template>
            <el-menu-item index="/user"> 用户 </el-menu-item>
            <el-menu-item v-if="store.admin" index="/system"> 系统 </el-menu-item>
            <el-menu-item index="/logout" @click="logout"> 退出 </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else index="/login"> 登录 </el-menu-item>
        </el-menu>
      </el-header>

      <el-main v-if="mounted">
        <el-config-provider :locale="zhCn">
          <RouterView />
        </el-config-provider>
      </el-main>
    </el-container>
  </div>
</template>

<style>
#mode {
  margin-top: 30px;
  margin-left: 12px;
}

.flex-grow {
  flex-grow: 1;
}

.el-alert {
  width: 98%;
  margin: 0 20px;
}

.el-alert__content {
  width: 100%;
}

.el-alert .el-alert__close-btn {
  font-size: var(--el-alert-close-font-size);
  opacity: 1;
  position: absolute;
  top: 6px;
  right: 6px;
  cursor: pointer;
}
</style>
