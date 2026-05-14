# Plugin Management Mobile Drag Disable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Disable drag sorting for plugin management and filter management on screens narrower than 768px while keeping desktop drag sorting unchanged.

**Architecture:** Keep the behavior isolated to the subscription page frontend. Extract the screen-width gating into a small helper that can be tested with Node's built-in test runner, then make `SubscriptionsView.vue` create or destroy `Sortable` instances based on that helper and current window width.

**Tech Stack:** Vue 3, TypeScript, SortableJS, Node `--test`

---

### Task 1: Add the failing responsive gating test

**Files:**
- Create: `web-ui/src/utils/pluginDragSupport.mjs`
- Create: `web-ui/src/utils/pluginDragSupport.test.mjs`

- [ ] **Step 1: Write the failing test**

```js
import test from 'node:test'
import assert from 'node:assert/strict'

import { isPluginDragEnabledForWidth } from './pluginDragSupport.mjs'

test('disables plugin drag sorting below 768px', () => {
  assert.equal(isPluginDragEnabledForWidth(767), false)
})

test('keeps plugin drag sorting enabled at 768px and above', () => {
  assert.equal(isPluginDragEnabledForWidth(768), true)
  assert.equal(isPluginDragEnabledForWidth(1024), true)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web-ui/src/utils/pluginDragSupport.test.mjs`
Expected: FAIL because `isPluginDragEnabledForWidth` is not exported yet.

- [ ] **Step 3: Write minimal implementation**

```js
export const MOBILE_PLUGIN_DRAG_MAX_WIDTH = 767

export const isPluginDragEnabledForWidth = (width) => width > MOBILE_PLUGIN_DRAG_MAX_WIDTH
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test web-ui/src/utils/pluginDragSupport.test.mjs`
Expected: PASS

### Task 2: Wire the responsive gating into the subscription page

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.vue`
- Use: `web-ui/src/utils/pluginDragSupport.mjs`
- Verify: `web-ui/src/utils/pluginDragSupport.test.mjs`

- [ ] **Step 1: Add responsive state and helper usage**

```ts
import { MOBILE_PLUGIN_DRAG_MAX_WIDTH, isPluginDragEnabledForWidth } from '@/utils/pluginDragSupport.mjs'

const pluginDragEnabled = ref(isPluginDragEnabledForWidth(window.innerWidth))

const syncPluginDragEnabled = () => {
  pluginDragEnabled.value = isPluginDragEnabledForWidth(window.innerWidth)
}
```

- [ ] **Step 2: Guard Sortable creation and destroy on small screens**

```ts
const enablePluginRowDrop = () => {
  if (!pluginDragEnabled.value) {
    pluginSortable?.destroy()
    pluginSortable = null
    return
  }
  // existing Sortable setup
}

const enablePluginFilterRowDrop = () => {
  if (!pluginDragEnabled.value) {
    pluginFilterSortable?.destroy()
    pluginFilterSortable = null
    return
  }
  // existing Sortable setup
}
```

- [ ] **Step 3: React to resize and expose small-screen hint**

```ts
watch(pluginDragEnabled, () => {
  nextTick(() => {
    if (pluginVisible.value) {
      enablePluginRowDrop()
    }
    if (pluginFilterVisible.value) {
      enablePluginFilterRowDrop()
    }
  })
})

onMounted(() => {
  window.addEventListener('resize', syncPluginDragEnabled)
})

onUnmounted(() => {
  window.removeEventListener('resize', syncPluginDragEnabled)
})
```

- [ ] **Step 4: Update the template and styles for disabled small-screen drag**

```vue
<el-alert
  v-if="!pluginDragEnabled"
  title="小屏幕不支持拖拽排序，请在大屏设备操作"
  type="info"
  :closable="false"
/>

<span :class="pluginDragEnabled ? 'pointer' : 'order-text'">{{ scope.row.sortOrder }}</span>
```

- [ ] **Step 5: Run targeted verification**

Run: `node --test web-ui/src/utils/pluginDragSupport.test.mjs`
Expected: PASS

Run: `npm run type-check`
Expected: PASS
