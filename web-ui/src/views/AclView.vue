<template>
  <div class="files">
    <h1>AList访问控制</h1>
    <el-row justify="end">
      <el-button @click="load"> 刷新 </el-button>
      <el-button type="primary" @click="handleAdd"> 添加 </el-button>
    </el-row>
    <div class="space" />

    <el-table :data="rules" border style="width: 100%">
      <el-table-column prop="name" label="名称/Token" />
      <el-table-column prop="url" label="默认订阅地址" sortable>
        <template #default="scope">
          <a :href="currentUrl + '/sub/' + scope.row.name + '/0'" target="_blank">
            {{ currentUrl }}/sub/{{ scope.row.name }}/0
          </a>
        </template>
      </el-table-column>
      <el-table-column prop="include" label="白名单" />
      <el-table-column prop="exclude" label="黑名单" />
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button type="primary" size="small" @click="handleEdit(scope.row)"> 编辑 </el-button>
          <el-button type="danger" size="small" @click="handleDelete(scope.row)"> 删除 </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="divider" />

    <div>
      <h3>说明</h3>
      <ul>
        <li>本功能不需要开启安全订阅</li>
        <li>但是如果开启了安全订阅，名称需要在安全Token列表内才能访问</li>
        <li>网页播放使用第一个安全Token，建议为网页播放设置一个专用的安全Token</li>
        <li>黑名单优先级高于白名单</li>
        <li>本功能对直接访问AList 5344无效</li>
      </ul>
    </div>

    <el-dialog v-model="formVisible" :title="dialogTitle">
      <el-form :model="form" label-width="120">
        <el-form-item label="名称/Token" required>
          <el-input v-model="form.name" autocomplete="off" />
          <a v-if="form.name" :href="currentUrl + '/sub/' + form.name + '/0'" target="_blank">
            {{ currentUrl }}/sub/{{ form.name }}/0
          </a>
        </el-form-item>
        <el-form-item label="白名单">
          <el-input
            v-model="form.include"
            type="textarea"
            :rows="5"
            :placeholder="'多行路径\n每行一个路径\n路径以/开头'"
          />
        </el-form-item>
        <el-form-item label="黑名单">
          <el-input
            v-model="form.exclude"
            type="textarea"
            :rows="5"
            :placeholder="'多行规则\n每行一个路径或者关键词\n路径以/开头'"
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

    <el-dialog v-model="dialogVisible" title="删除规则" width="30%">
      <p>是否删除规则 - {{ form.id }}</p>
      <p>{{ form.name }}</p>
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
import { onMounted, ref } from "vue";
import { api } from "@/services/api";

interface Rule {
  id: number;
  name: string;
  include: string;
  exclude: string;
}

const currentUrl = window.location.origin;
const updateAction = ref(false);
const dialogTitle = ref("");
const rules = ref<Rule[]>([]);
const formVisible = ref(false);
const dialogVisible = ref(false);
const form = ref<Rule>({
  id: 0,
  name: "",
  include: "",
  exclude: "",
});

const handleAdd = () => {
  dialogTitle.value = "添加规则";
  updateAction.value = false;
  form.value = {
    id: 0,
    name: "",
    include: "",
    exclude: "",
  };
  formVisible.value = true;
};

const handleEdit = (file: Rule) => {
  dialogTitle.value = "更新规则 - " + file.id;
  updateAction.value = true;
  const rule = Object.assign({}, file);
  rule.include = rule.include.replace(/,/g, "\n");
  rule.exclude = rule.exclude.replace(/,/g, "\n");
  form.value = rule;
  formVisible.value = true;
};

const handleDelete = (data: any) => {
  form.value = data;
  dialogVisible.value = true;
};

const deleteSub = () => {
  dialogVisible.value = false;
  api.delete("/api/tenants/" + form.value.id).then(() => {
    load();
  });
};

const handleCancel = () => {
  formVisible.value = false;
};

const handleConfirm = () => {
  const url = updateAction.value ? "/api/tenants/" + form.value.id : "/api/tenants";
  const rule = Object.assign({}, form.value);
  rule.include = rule.include.replace(/\n/g, ",");
  rule.exclude = rule.exclude.replace(/\n/g, ",");
  api.post(url, rule).then(() => {
    formVisible.value = false;
    load();
  });
};

const load = () => {
  api.get("/api/tenants").then((data) => {
    rules.value = data;
  });
};

onMounted(() => {
  load();
});
</script>

<style scoped>
.space {
  margin-bottom: 6px;
}

.divider {
  margin-top: 30px;
}

.json pre {
  height: 600px;
  overflow: scroll;
}
</style>
