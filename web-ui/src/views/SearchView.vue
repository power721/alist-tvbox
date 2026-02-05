<template>
  <div class="search">
    <h2>API地址</h2>
    <div class="description">
      <a :href="currentUrl + getPath(type) + '/' + store.token + '?wd=' + keyword" target="_blank"
        >{{ currentUrl }}{{ getPath(type) }}/{{ store.token }}?wd={{ keyword }}</a
      >
    </div>

    <div>
      <el-input v-model="keyword" @change="search" />
      <el-button type="primary" :disabled="!keyword" @click="search"> 搜索 </el-button>
      <el-button v-if="store.admin" type="primary" @click="showDialog"> 设置 </el-button>
    </div>

    <el-form-item label="类型" label-width="140">
      <el-radio-group v-model="type" class="ml-4" @change="search">
        <el-radio label="1" size="large"> 点播模式 </el-radio>
        <el-radio label="" size="large"> 网盘模式 </el-radio>
        <el-radio label="2" size="large"> BiliBili </el-radio>
        <el-radio label="4" size="large"> Emby </el-radio>
        <el-radio label="5" size="large"> Jellyfin </el-radio>
        <el-radio label="6" size="large"> 鱼佬盘搜 </el-radio>
      </el-radio-group>
    </el-form-item>

    <a v-if="store.admin" href="/#/meta">豆瓣电影数据列表</a>
    <span v-if="store.admin" class="divider" />
    <a v-if="store.admin" href="/#/tmdb">TMDB电影数据列表</a>

    <el-table
      v-if="(type == '' || type == '1') && config"
      :data="config.list"
      border
      style="width: 100%"
    >
      <el-table-column prop="vod_name" label="名称" width="300">
        <template #default="scope">
          <a :href="'/#/vod' + scope.row.vod_content" target="_blank">
            {{ scope.row.vod_name }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_content" label="路径">
        <template #default="scope">
          <a :href="'/#/vod' + scope.row.vod_content" target="_blank">
            {{ scope.row.vod_content }}
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="vod_year" label="年份" width="90" />
      <el-table-column prop="vod_remarks" label="评分" width="100" />
    </el-table>

    <el-table v-if="type == '6' && config" :data="config.list" border style="width: 100%">
      <el-table-column prop="vod_name" label="名称">
        <template #default="scope">
          <a :href="'/#/vod?link=' + scope.row.vod_id" target="_blank">
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
      <el-table-column prop="vod_remarks" label="类型" width="100" />
    </el-table>

    <h2 v-if="type != '6'">API返回数据</h2>
    <div v-if="type != '6'" class="data">
      <json-viewer
        :value="config"
        expanded
        copyable
        show-double-quotes
        :show-array-index="false"
        :expand-depth="3"
      />
    </div>

    <el-dialog v-model="dialogVisible" title="配置搜索源">
      <el-form label-width="auto">
        <el-form-item label="搜索文件">
          <el-checkbox-group v-model="form.searchSources">
            <el-checkbox v-for="file in form.files" :key="file" :value="file" name="index">
              {{ file }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="排除路径">
          <el-input
            v-model="form.excludedPaths"
            type="textarea"
            :rows="15"
            :placeholder="'多行以/开头的路径'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="update">更新</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { api } from "@/services/api";
import { ElMessage } from "element-plus";
import { store } from "@/services/store";

const type = ref("1");
const keyword = ref("");
const config = ref<any>("");
const dialogVisible = ref(false);
const currentUrl = window.location.origin;
const form = ref({
  files: [],
  searchSources: [],
  excludedPaths: "",
});

const getPath = (type: string) => {
  if (type == "1") {
    return "/vod1";
  } else if (type == "2") {
    return "/bilibili";
  } else if (type == "3") {
    return "/youtube";
  } else if (type == "4") {
    return "/emby";
  } else if (type == "5") {
    return "/jellyfin";
  } else if (type == "6") {
    return "/pansou";
  } else {
    return "/vod";
  }
};

const search = function () {
  if (!keyword.value) {
    return;
  }
  config.value = "";
  api
    .get(getPath(type.value) + "/" + store.token + "?ac=web&wd=" + keyword.value.trim())
    .then((data) => {
      config.value = data;
    });
};

const showDialog = () => {
  api.get("/api/index-files/settings").then((data) => {
    data.excludedPaths = data.excludedPaths.replace(/,/g, "\n");
    form.value = data;
    dialogVisible.value = true;
  });
};

const update = () => {
  const rule = Object.assign({}, form.value);
  rule.excludedPaths = rule.excludedPaths.replace(/\n/g, ",");
  api.post("/api/index-files/settings", rule).then(() => {
    ElMessage.success("更新成功");
  });
};
</script>

<style scoped>
.description {
  margin-bottom: 12px;
}

.divider {
  margin-left: 24px;
}
</style>
