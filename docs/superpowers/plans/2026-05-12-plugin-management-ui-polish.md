# Plugin Management UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish the subscription-page plugin manager so the table shows plugin versions, timestamps render without timezone text, repository import shows visible loading progress, and the repository URL persists in browser local storage.

**Architecture:** Keep all behavior changes inside `web-ui/src/views/SubscriptionsView.vue`. Use the existing `/api/plugins` and `/api/plugins/import` endpoints, add a small local formatter/helper layer for timestamps and local-storage-backed form state, and expose import progress with Element Plus loading props plus an indeterminate progress bar.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Axios.

---

### Task 1: Add the missing plugin-management UI state and rendering

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.vue`

- [ ] **Step 1: Add the missing plugin fields and local state**

```ts
interface Plugin {
  id: number
  name: string
  url: string
  enabled: boolean
  sortOrder: number
  version: number | null
  extend: string
  sourceName: string
  lastCheckedAt: string
  lastError: string
}

const PLUGIN_REPO_URL_KEY = 'plugin_repo_url'
const importingPlugins = ref(false)
```

- [ ] **Step 2: Render version and formatted time in the table**

```vue
<el-table-column prop="version" label="版本" width="90"/>
<el-table-column label="最近检查" width="180">
  <template #default="scope">
    <span>{{ formatPluginCheckedAt(scope.row.lastCheckedAt) }}</span>
  </template>
</el-table-column>
```

- [ ] **Step 3: Persist the repository URL in local storage and restore it when the dialog opens**

```ts
pluginImportForm.value.url = localStorage.getItem(PLUGIN_REPO_URL_KEY) || ''

watch(() => pluginImportForm.value.url, (value) => {
  localStorage.setItem(PLUGIN_REPO_URL_KEY, value)
})
```

- [ ] **Step 4: Show import loading state while `/api/plugins/import` is running**

```vue
<el-input v-model="pluginImportForm.url" :disabled="importingPlugins" />
<el-button type="primary" :loading="importingPlugins" :disabled="!pluginImportForm.url.trim()" @click="importPlugins">
  导入仓库
</el-button>
<el-progress v-if="importingPlugins" :percentage="100" :indeterminate="true" :duration="5" />
```

- [ ] **Step 5: Verify with frontend checks**

Run: `npm run type-check`
Expected: PASS

Run: `npm run build`
Expected: PASS
