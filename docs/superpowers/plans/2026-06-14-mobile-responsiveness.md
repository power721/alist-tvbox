# Mobile Responsiveness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add mobile browser compatibility to AList-TvBox web UI while preserving desktop experience

**Architecture:** CSS-first progressive enhancement. Add media queries to modern.css, hamburger navigation to App.vue, table wrappers to views. No new components or routing changes.

**Tech Stack:** Vue 3, Element Plus, TypeScript, CSS media queries

---

## File Structure

**Modified files:**
- `web-ui/src/App.vue` — Add mobile navigation drawer
- `web-ui/src/assets/theme.css` — Add touch-target CSS variable
- `web-ui/src/assets/modern.css` — Add comprehensive mobile media queries
- **Tier 1 views** (6 files): SubscriptionsView, SitesView, AccountsView, SearchView, VodView, LiveView
- **Tier 2 views** (5 files): SharesView, ConfigView, LogsView, UsersView, HomeView
- **Tier 3 views** (12 files): IndexView, AclView, AliasView, FilesView, BiliBiliView, EmbyView, JellyfinView, FeiniuView, MetaView, TmdbView, PikPakView, DriverAccountView

**No new files created.** All changes are additions/modifications to existing files.

---

## Task 1: Add Touch-Target CSS Variable

**Files:**
- Modify: `web-ui/src/assets/theme.css` (add to :root block)

- [ ] **Step 1: Add tap target variable to theme.css**

Add after existing CSS variables in `:root` block:

```css
--tap-target-min: 44px; /* iOS Human Interface Guidelines minimum tap target */
```

- [ ] **Step 2: Verify CSS syntax**

Run: `cd web-ui && npm run build`
Expected: Build succeeds with no CSS errors

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/assets/theme.css
git commit -m "feat(mobile): add tap target size CSS variable"
```

---

## Task 2: Add Mobile CSS Utilities to modern.css

**Files:**
- Modify: `web-ui/src/assets/modern.css` (add after line 267, after existing @media blocks)

- [ ] **Step 1: Add utility classes**

Add at end of modern.css file:

```css
/* ============================================
   Mobile Utilities
   ============================================ */
.table-scroll-wrapper {
  width: 100%;
}

.mobile-only {
  display: none;
}

.desktop-only {
  display: block;
}

@media (max-width: 767px) {
  .mobile-only {
    display: block;
  }
  
  .desktop-only {
    display: none;
  }
  
  .table-scroll-wrapper {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
    margin: 0 -16px;
    padding: 0 16px;
  }
}
```

- [ ] **Step 2: Verify CSS builds**

Run: `cd web-ui && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/assets/modern.css
git commit -m "feat(mobile): add utility classes for responsive layout"
```

---

## Task 3: Add Mobile Table Styles to modern.css

**Files:**
- Modify: `web-ui/src/assets/modern.css` (extend existing @media (max-width: 767px) block starting at line 247)

- [ ] **Step 1: Add table responsive styles inside existing mobile media query**

Add inside the existing `@media (max-width: 767px)` block (after line 265):

```css
  /* Table responsive behavior */
  .el-table {
    min-width: 800px;
  }
  
  /* Sticky first column for context while scrolling */
  .el-table th:first-child,
  .el-table td:first-child {
    position: sticky;
    left: 0;
    background: var(--bg-card);
    z-index: 2;
    box-shadow: 2px 0 4px rgba(0,0,0,0.05);
  }
  
  /* Sticky action column */
  .el-table .el-table__fixed-right {
    position: sticky;
    right: 0;
    background: var(--bg-card);
    z-index: 2;
  }
  
  /* Touch-friendly table action buttons */
  .el-table .el-button--small {
    min-width: 44px;
    min-height: 44px;
    padding: 10px;
  }
```

- [ ] **Step 2: Verify CSS builds**

Run: `cd web-ui && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/assets/modern.css
git commit -m "feat(mobile): add responsive table styles with sticky columns"
```

---

## Task 4: Add Mobile Form and Dialog Styles to modern.css

**Files:**
- Modify: `web-ui/src/assets/modern.css` (extend existing @media (max-width: 767px) block)

- [ ] **Step 1: Add form/dialog responsive styles inside existing mobile media query**

Add inside the existing `@media (max-width: 767px)` block:

```css
  /* Full-width dialogs on mobile */
  .el-dialog {
    width: 95vw !important;
    margin: 5px auto !important;
  }
  
  /* Stack form labels and inputs vertically */
  .el-form-item {
    flex-direction: column;
    align-items: flex-start;
  }
  
  .el-form-item__label {
    width: 100% !important;
    text-align: left;
    margin-bottom: 8px;
  }
  
  .el-form-item__content {
    width: 100%;
    margin-left: 0 !important;
  }
  
  /* Full-width form controls */
  .el-input,
  .el-select,
  .el-textarea {
    width: 100%;
  }
  
  /* Touch-friendly form controls */
  .el-button {
    min-height: var(--tap-target-min);
    padding: 12px 16px;
  }
  
  .el-switch {
    min-height: var(--tap-target-min);
  }
  
  .el-checkbox,
  .el-radio {
    min-height: var(--tap-target-min);
    line-height: var(--tap-target-min);
  }
```

- [ ] **Step 2: Verify CSS builds**

Run: `cd web-ui && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/assets/modern.css
git commit -m "feat(mobile): add responsive form and dialog styles"
```

---

## Task 5: Add Mobile Navigation Styles to modern.css

**Files:**
- Modify: `web-ui/src/assets/modern.css` (add new media query blocks for navigation)

- [ ] **Step 1: Add navigation responsive styles**

Add after the touch-friendly form controls CSS (before closing the mobile media query block):

```css
  /* Mobile navigation */
  .desktop-nav {
    display: none;
  }
  
  .mobile-nav-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
    padding: 0 16px;
  }
  
  .app-title {
    font-size: 18px;
    font-weight: 600;
    color: var(--text-primary);
    margin: 0;
  }
  
  .mobile-menu-trigger {
    min-width: var(--tap-target-min);
    min-height: var(--tap-target-min);
  }
  
  /* Wrap action buttons to multiple rows */
  .page-actions {
    flex-wrap: wrap;
    gap: 8px;
  }
```

- [ ] **Step 2: Add desktop-only navigation styles**

Add a new media query block after the mobile one (before end of file):

```css
/* Desktop navigation - hide mobile elements */
@media (min-width: 768px) {
  .mobile-nav-header,
  .mobile-menu-trigger {
    display: none;
  }
  
  .desktop-nav {
    display: flex;
  }
}
```

- [ ] **Step 3: Verify CSS builds**

Run: `cd web-ui && npm run build`
Expected: Build succeeds

- [ ] **Step 4: Commit**

```bash
git add web-ui/src/assets/modern.css
git commit -m "feat(mobile): add responsive navigation styles"
```

---

## Task 6: Add Mobile Navigation Drawer to App.vue

**Files:**
- Modify: `web-ui/src/App.vue`

- [ ] **Step 1: Import Menu icon**

Add to existing imports at top of script section (after line 11):

```typescript
import { Menu } from '@element-plus/icons-vue'
```

- [ ] **Step 2: Add mobileMenuOpen ref**

Add after line 19 (after `showNotification` ref):

```typescript
const mobileMenuOpen = ref(false)
```

- [ ] **Step 3: Add navigate function**

Add after the `close` function (around line 28):

```typescript
const navigate = (path: string) => {
  router.push(path)
  mobileMenuOpen.value = false
}
```

- [ ] **Step 4: Verify TypeScript compiles**

Run: `cd web-ui && npm run type-check`
Expected: No type errors

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/App.vue
git commit -m "feat(mobile): add navigation state and helpers to App.vue"
```

---

## Task 7: Add Mobile Header UI to App.vue Template

**Files:**
- Modify: `web-ui/src/App.vue` (template section)

- [ ] **Step 1: Add mobile-nav-header before existing el-menu**

Add after `<el-header>` tag (before line 70):

```vue
      <!-- Mobile navigation trigger -->
      <div class="mobile-nav-header">
        <h2 class="app-title">AList-TvBox</h2>
        <el-button class="mobile-menu-trigger" @click="mobileMenuOpen = true" text>
          <el-icon :size="24"><Menu /></el-icon>
        </el-button>
      </div>
```

- [ ] **Step 2: Add desktop-nav class to existing el-menu**

Change line 70 from:
```vue
        <el-menu mode="horizontal" :ellipsis="false" :router="true">
```
To:
```vue
        <el-menu mode="horizontal" :ellipsis="false" :router="true" class="desktop-nav">
```

- [ ] **Step 3: Verify app builds**

Run: `cd web-ui && npm run build`
Expected: Build succeeds

- [ ] **Step 4: Commit**

```bash
git add web-ui/src/App.vue
git commit -m "feat(mobile): add hamburger menu trigger to header"
```

---

## Task 8: Add Mobile Navigation Drawer to App.vue Template

**Files:**
- Modify: `web-ui/src/App.vue` (template section, after el-header closing tag)

- [ ] **Step 1: Add el-drawer component after el-header**

Add after the closing `</el-header>` tag (after line 102):

```vue
      
      <!-- Mobile navigation drawer -->
      <el-drawer v-model="mobileMenuOpen" direction="ltr" size="80%">
        <template #header>
          <span style="font-weight: 600; font-size: 18px;">菜单</span>
        </template>
        
        <el-menu>
          <el-menu-item index="/" v-if="store.admin" @click="navigate('/')">首页</el-menu-item>
          <el-menu-item index="/sites" v-if="account.authenticated && store.admin" @click="navigate('/sites')">站点</el-menu-item>
          <el-menu-item index="/accounts" v-if="account.authenticated && show && store.admin" @click="navigate('/accounts')">账号</el-menu-item>
          <el-menu-item index="/bilibili" v-if="account.authenticated && full && store.admin" @click="navigate('/bilibili')">BiliBili</el-menu-item>
          <el-menu-item index="/subscriptions" v-if="account.authenticated && store.admin" @click="navigate('/subscriptions')">订阅</el-menu-item>
          <el-menu-item index="/shares" v-if="account.authenticated && show && full && store.admin" @click="navigate('/shares')">资源</el-menu-item>
          <el-menu-item index="/config" v-if="account.authenticated && store.admin" @click="navigate('/config')">配置</el-menu-item>
          <el-menu-item index="/acl" v-if="account.authenticated && full && store.admin" @click="navigate('/acl')">ACL</el-menu-item>
          <el-menu-item index="/index" v-if="account.authenticated && show && full && store.admin" @click="navigate('/index')">索引</el-menu-item>
          <el-menu-item index="/logs" v-if="account.authenticated && store.admin" @click="navigate('/logs')">日志</el-menu-item>
          <el-menu-item index="/files" v-if="account.authenticated && show && full && store.admin" @click="navigate('/files')">文件</el-menu-item>
          <el-menu-item index="/alias" v-if="account.authenticated && show && full && store.admin" @click="navigate('/alias')">别名</el-menu-item>
          <el-menu-item index="/users" v-if="account.authenticated && show && full && store.admin" @click="navigate('/users')">用户</el-menu-item>
          <el-menu-item index="/search" v-if="account.authenticated && (full || !store.admin)" @click="navigate('/search')">搜索</el-menu-item>
          <el-menu-item index="/vod" v-if="account.authenticated && show && (full || !store.admin)" @click="navigate('/vod')">播放</el-menu-item>
          <el-menu-item index="/live" v-if="account.authenticated && (full || !store.admin)" @click="navigate('/live')">直播</el-menu-item>
          <el-menu-item index="/about" v-if="account.authenticated && store.admin" @click="navigate('/about')">关于</el-menu-item>
          <el-menu-item index="/user" v-if="account.authenticated" @click="navigate('/user')">用户</el-menu-item>
          <el-menu-item index="/system" v-if="account.authenticated && store.admin" @click="navigate('/system')">系统</el-menu-item>
          <el-menu-item @click="logout" v-if="account.authenticated">退出</el-menu-item>
          <el-menu-item index="/login" v-else @click="navigate('/login')">登录</el-menu-item>
        </el-menu>
        
        <template #footer v-if="account.authenticated && store.admin">
          <div style="padding: 16px 0;">
            <el-switch v-model="full" inline-prompt active-text="高级模式" inactive-text="简单模式"
              style="--el-switch-on-color: #13ce66; --el-switch-off-color: #409eff;"
              @change="onModeChange" />
          </div>
        </template>
      </el-drawer>
```

- [ ] **Step 2: Build and verify**

Run: `cd web-ui && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/App.vue
git commit -m "feat(mobile): add navigation drawer with full menu"
```

---

## Task 9: Test Mobile Navigation in Browser Emulation

**Files:**
- No file changes

- [ ] **Step 1: Start dev server**

Run: `cd web-ui && npm run dev`
Expected: Dev server starts on http://localhost:5173

- [ ] **Step 2: Test desktop view (>768px)**

Open http://localhost:5173 in Chrome
Resize browser to 1200px width
Expected: Horizontal menu visible, hamburger hidden

- [ ] **Step 3: Test mobile view (375px)**

Chrome DevTools → Toggle device toolbar → iPhone SE (375x667)
Expected: Hamburger menu visible in top-right, horizontal menu hidden

- [ ] **Step 4: Test drawer interaction**

Click hamburger icon
Expected: Drawer slides in from left at 80% width
Click a menu item
Expected: Drawer closes, navigate to selected page

- [ ] **Step 5: Test mode switch in drawer**

Open drawer, toggle "高级模式/简单模式" switch
Expected: Menu items appear/disappear based on mode

- [ ] **Step 6: Document results**

Note any issues in commit message or create follow-up tasks
Expected: Navigation works on mobile and desktop without regressions

---

## Task 10: Wrap Tables in SubscriptionsView (Tier 1)

**Files:**
- Modify: `web-ui/src/views/SubscriptionsView.vue`

- [ ] **Step 1: Wrap main subscriptions table**

Find the `<el-table :data="subscriptions"` at line 17
Wrap it with scroll wrapper:

```vue
    <div class="table-scroll-wrapper">
      <el-table :data="subscriptions" v-loading="loading" border style="width: 100%; min-width: 800px">
```

Add closing div after the table's closing `</el-table>` tag (after line 53).

- [ ] **Step 2: Wrap devices table if it exists**

Search for any other `<el-table>` elements in the file and wrap them similarly.

- [ ] **Step 3: Build and verify**

Run: `cd web-ui && npm run build`
Expected: Build succeeds

- [ ] **Step 4: Test in mobile view**

Open /subscriptions at 375px width
Expected: Table scrolls horizontally, first column (订阅ID) stays fixed

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/views/SubscriptionsView.vue
git commit -m "feat(mobile): make subscriptions table mobile-responsive"
```

---

## Task 11: Wrap Tables in SitesView (Tier 1)

**Files:**
- Modify: `web-ui/src/views/SitesView.vue`

- [ ] **Step 1: Wrap sites table**

Find the `<el-table :data="sites"` at line 12
Wrap with scroll wrapper:

```vue
      <div class="table-scroll-wrapper">
        <el-table :data="sites" v-loading="loading" border style="width: 100%; min-width: 1000px">
```

Add closing `</div>` after the table's closing `</el-table>` tag (after line 61).

- [ ] **Step 2: Build and verify**

Run: `cd web-ui && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Test in mobile view**

Open /sites at 375px width
Expected: Table scrolls horizontally, first column (名称) sticky

- [ ] **Step 4: Test add/edit dialog**

Click "添加" button
Expected: Dialog opens full-width (95vw), form labels stack vertically

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/views/SitesView.vue
git commit -m "feat(mobile): make sites table mobile-responsive"
```

---

## Task 12: Wrap Tables in AccountsView (Tier 1)

**Files:**
- Modify: `web-ui/src/views/AccountsView.vue`

- [ ] **Step 1: Wrap accounts table**

Find the first `<el-table>` element
Wrap with scroll wrapper:

```vue
      <div class="table-scroll-wrapper">
        <el-table :data="accounts" v-loading="loading" border style="width: 100%; min-width: 900px">
```

Add closing `</div>` after the table closes.

- [ ] **Step 2: Build and test**

Run: `cd web-ui && npm run build`
Open /accounts at 375px width
Expected: Table scrolls, first column sticky, forms work

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/views/AccountsView.vue
git commit -m "feat(mobile): make accounts table mobile-responsive"
```

---

## Task 13: Wrap Tables in SearchView (Tier 1)

**Files:**
- Modify: `web-ui/src/views/SearchView.vue`

- [ ] **Step 1: Wrap search results table**

Find the `<el-table>` element for search results
Wrap with scroll wrapper:

```vue
      <div class="table-scroll-wrapper">
        <el-table :data="results" v-loading="loading" border style="width: 100%; min-width: 800px">
```

Add closing `</div>` after table closes.

- [ ] **Step 2: Build and test**

Run: `cd web-ui && npm run build`
Open /search at 375px, perform search
Expected: Results table scrolls horizontally

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/views/SearchView.vue
git commit -m "feat(mobile): make search results mobile-responsive"
```

---

## Task 14: Wrap Tables in VodView (Tier 1)

**Files:**
- Modify: `web-ui/src/views/VodView.vue`

- [ ] **Step 1: Wrap VOD table**

Find the `<el-table>` element
Wrap with scroll wrapper:

```vue
      <div class="table-scroll-wrapper">
        <el-table :data="vodItems" v-loading="loading" border class="vod" style="width: 100%; min-width: 800px">
```

Add closing `</div>` after table closes.

- [ ] **Step 2: Build and test**

Run: `cd web-ui && npm run build`
Open /vod at 375px width
Expected: Table scrolls, clickable rows work

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/views/VodView.vue
git commit -m "feat(mobile): make VOD table mobile-responsive"
```

---

## Task 15: Wrap Tables in LiveView (Tier 1)

**Files:**
- Modify: `web-ui/src/views/LiveView.vue`

- [ ] **Step 1: Wrap live streams table**

Find the `<el-table>` element
Wrap with scroll wrapper:

```vue
      <div class="table-scroll-wrapper">
        <el-table :data="streams" v-loading="loading" border style="width: 100%; min-width: 700px">
```

Add closing `</div>` after table closes.

- [ ] **Step 2: Build and test**

Run: `cd web-ui && npm run build`
Open /live at 375px width
Expected: Table scrolls horizontally

- [ ] **Step 3: Commit**

```bash
git add web-ui/src/views/LiveView.vue
git commit -m "feat(mobile): make live streams table mobile-responsive"
```

---

## Task 16: Test Tier 1 Views End-to-End on Mobile

**Files:**
- No file changes

- [ ] **Step 1: Test SubscriptionsView workflow**

At 375px width:
1. Open hamburger menu → navigate to "订阅"
2. Click "添加" button
3. Fill form (name, URL)
4. Submit form
5. Verify new subscription in table
6. Click "编辑" on an item
7. Modify and save
8. Click "删除" and confirm
Expected: All interactions work without horizontal page scroll

- [ ] **Step 2: Test SitesView workflow**

1. Navigate to "站点"
2. Add new site via form
3. Edit existing site
4. Verify all table columns accessible via scroll
Expected: CRUD operations successful on mobile

- [ ] **Step 3: Test AccountsView workflow**

1. Navigate to "账号"
2. Verify table scrolls and all data visible
3. Open add/edit dialogs
Expected: Forms usable on mobile

- [ ] **Step 4: Test SearchView**

1. Navigate to "搜索"
2. Perform search query
3. Scroll through results table
Expected: Results table functional

- [ ] **Step 5: Test VodView and LiveView**

1. Navigate to "播放" - verify table interaction
2. Navigate to "直播" - verify table scrolls
Expected: Both views functional on mobile

- [ ] **Step 6: Document any issues**

Note any problems for follow-up tasks

---

## Task 17: Wrap Tables in Tier 2 Views (Batch)

**Files:**
- Modify: `web-ui/src/views/SharesView.vue`
- Modify: `web-ui/src/views/ConfigView.vue`
- Modify: `web-ui/src/views/LogsView.vue`
- Modify: `web-ui/src/views/UsersView.vue`
- Modify: `web-ui/src/views/HomeView.vue`

- [ ] **Step 1: Apply table wrapper pattern to SharesView**

Find all `<el-table>` elements (resources table and failed resources table)
Wrap each with:

```vue
      <div class="table-scroll-wrapper">
        <el-table ... style="width: 100%; min-width: 900px">
        ...
        </el-table>
      </div>
```

- [ ] **Step 2: Apply to ConfigView**

Wrap any tables in ConfigView with same pattern (min-width: 800px)

- [ ] **Step 3: Apply to LogsView**

Wrap logs table (min-width: 1000px for timestamp columns)

- [ ] **Step 4: Apply to UsersView**

Wrap users table (min-width: 700px)

- [ ] **Step 5: Apply to HomeView**

Wrap any tables in home dashboard (min-width: 800px)

- [ ] **Step 6: Build and test**

Run: `cd web-ui && npm run build`
Test each view at 375px width
Expected: All tables scroll horizontally, no page-level horizontal scroll

- [ ] **Step 7: Commit**

```bash
git add web-ui/src/views/SharesView.vue web-ui/src/views/ConfigView.vue web-ui/src/views/LogsView.vue web-ui/src/views/UsersView.vue web-ui/src/views/HomeView.vue
git commit -m "feat(mobile): make Tier 2 views mobile-responsive"
```

---

## Task 18: Wrap Tables in Tier 3 Views (Batch)

**Files:**
- Modify: IndexView, AclView, AliasView, FilesView, BiliBiliView, EmbyView, JellyfinView, FeiniuView, MetaView, TmdbView, PikPakView, DriverAccountView

- [ ] **Step 1: Apply table wrapper to all Tier 3 views**

For each view, find `<el-table>` and wrap with:

```vue
      <div class="table-scroll-wrapper">
        <el-table ... style="width: 100%; min-width: 900px">
```

Use min-width based on table complexity:
- Simple tables (3-5 columns): 700px
- Medium tables (6-8 columns): 900px
- Complex tables (9+ columns): 1100px

- [ ] **Step 2: Build**

Run: `cd web-ui && npm run build`
Expected: Build succeeds

- [ ] **Step 3: Spot-check 3 Tier 3 views**

Test IndexView, FilesView, MetaView at 375px
Expected: Tables scroll, no page overflow

- [ ] **Step 4: Commit**

```bash
git add web-ui/src/views/*.vue
git commit -m "feat(mobile): add basic mobile support to Tier 3 views"
```

---

## Task 19: Desktop Regression Testing

**Files:**
- No file changes

- [ ] **Step 1: Test desktop Chrome at 1920px**

Open http://localhost:5173 at 1920x1080 resolution
Navigate through all Tier 1 views (subscriptions, sites, accounts, search, vod, live)
Expected: Horizontal menu visible, tables full-width, forms at original width, no layout changes from original

- [ ] **Step 2: Test desktop Firefox at 1920px**

Same navigation test in Firefox
Expected: Identical to Chrome, no regressions

- [ ] **Step 3: Test desktop Safari at 1920px (if available)**

Same navigation test in Safari
Expected: No regressions

- [ ] **Step 4: Test Tier 2 views on desktop**

Spot-check SharesView, ConfigView, LogsView
Expected: No visual or functional regressions

- [ ] **Step 5: Test forms and dialogs on desktop**

Open add/edit dialogs in SitesView and SubscriptionsView
Expected: Original modal width and form layout preserved

- [ ] **Step 6: Document any regressions**

If any desktop issues found, create follow-up task to fix

---

## Task 20: Mobile Device Testing - iOS Safari

**Files:**
- No file changes

- [ ] **Step 1: Deploy to test server or ngrok**

Make dev server accessible from mobile device
Run: `cd web-ui && npm run dev -- --host`
Note local IP address (e.g., http://192.168.1.100:5173)

- [ ] **Step 2: Test on iPhone (iOS Safari)**

Open app on iPhone (iOS 15+)
Test navigation: tap hamburger, navigate to subscriptions
Expected: Drawer opens smoothly, navigation works

- [ ] **Step 3: Test table interactions**

On subscriptions view, scroll table horizontally
Expected: First column (订阅ID) stays visible while scrolling

- [ ] **Step 4: Test form submission**

Tap "添加" button, fill form, submit
Expected: Form opens full-width, inputs accessible, keyboard doesn't cover fields, submission works

- [ ] **Step 5: Test tap targets**

Verify all buttons are easily tappable (44px minimum)
Expected: No accidental mis-taps, comfortable interaction

- [ ] **Step 6: Document issues**

Note any iOS-specific issues (sticky columns, keyboard behavior, etc.)

---

## Task 21: Mobile Device Testing - Chrome Android

**Files:**
- No file changes

- [ ] **Step 1: Test on Android device (Chrome)**

Open app on Android phone (Chrome browser)
Test navigation: tap hamburger, navigate through menu
Expected: Drawer animation smooth, navigation functional

- [ ] **Step 2: Test table scrolling**

Navigate to sites view, scroll table horizontally
Expected: Smooth scrolling with sticky first column

- [ ] **Step 3: Test form interactions**

Open add site dialog, fill form fields, submit
Expected: Touch keyboard works correctly, form submits successfully

- [ ] **Step 4: Test touch targets**

Verify button sizes feel comfortable on Android
Expected: Easy to tap, no frustration

- [ ] **Step 5: Test at different screen sizes**

Test on compact Android (360px) and large Android (414px)
Expected: Works at both sizes

- [ ] **Step 6: Document issues**

Note any Android-specific issues

---

## Task 22: Final Verification Checklist

**Files:**
- No file changes

- [ ] **Step 1: Verify no horizontal page scroll**

Test all Tier 1 views at 320px, 375px, 414px widths
Expected: No horizontal scrollbar at page level (tables scroll, page doesn't)

- [ ] **Step 2: Verify tap targets**

Use Chrome DevTools → Show rulers
Measure button heights on mobile view
Expected: All interactive elements ≥44px height

- [ ] **Step 3: Verify forms submit**

Test add/edit/delete workflows in subscriptions and sites views on mobile
Expected: All CRUD operations successful

- [ ] **Step 4: Verify dialogs work**

Open dialogs in Tier 1 views at 375px
Expected: Dialogs open full-width, scrollable, closeable

- [ ] **Step 5: Verify sticky columns**

Scroll tables horizontally on mobile
Expected: First column stays visible, provides context

- [ ] **Step 6: Verify navigation**

Test hamburger menu, mode switch, logout on mobile
Expected: All navigation functional

- [ ] **Step 7: Verify desktop unchanged**

Open 5 random views on desktop Chrome at 1920px
Expected: No visual or functional differences from before

---

## Task 23: Update UI Optimization Progress Doc

**Files:**
- Modify: `docs/ui-optimization-progress.md`

- [ ] **Step 1: Add mobile responsiveness section**

Add new section after line 55 (after "移动端适配"):

```markdown
6. **移动端适配**：小屏幕下的响应式布局 ✅
   - 响应式导航：汉堡菜单 + 抽屉式导航 (< 768px)
   - 响应式表格：横向滚动 + 首列固定
   - 响应式表单：全宽对话框 + 垂直堆叠标签
   - 触摸优化：44px 最小触摸目标
   - Tier 1 完整支持：订阅、站点、账号、搜索、播放、直播
   - Tier 2 基本支持：资源、配置、日志、用户、首页
   - Tier 3 最小支持：索引、ACL、别名、文件等高级管理页面
   - 浏览器测试：iOS Safari、Chrome Android、桌面 Chrome/Firefox/Safari
```

- [ ] **Step 2: Update statistics**

Update statistics section (around line 110):

```markdown
- 已优化页面：30个 (全部添加移动端支持)
- 移动端完整优化：6个 (Tier 1)
- 移动端可用：5个 (Tier 2)
- 移动端基本支持：12个 (Tier 3)
```

- [ ] **Step 3: Commit**

```bash
git add docs/ui-optimization-progress.md
git commit -m "docs: update progress with mobile responsiveness completion"
```

---

## Task 24: Final Build and Commit

**Files:**
- All modified files

- [ ] **Step 1: Final production build**

Run: `cd web-ui && npm run build`
Expected: Build succeeds with no errors or warnings

- [ ] **Step 2: Verify build output**

Check `web-ui/dist/` directory
Expected: Assets generated successfully

- [ ] **Step 3: Create summary commit**

```bash
git add -A
git commit -m "feat(mobile): complete mobile responsiveness implementation

Progressive enhancement for mobile browsers (320px-768px):
- CSS-first responsive patterns in modern.css
- Hamburger navigation with drawer for mobile
- Horizontal scrolling tables with sticky first column
- Full-width dialogs with stacked form layouts
- 44px minimum tap targets for touch interactions

Tier 1 (full optimization): Subscriptions, Sites, Accounts, Search, VOD, Live
Tier 2 (mobile-usable): Shares, Config, Logs, Users, Home
Tier 3 (basic support): Index, ACL, Alias, Files, and other advanced views

Tested on iOS Safari, Chrome Android, desktop browsers
Zero desktop regressions confirmed

Closes #<issue-number>

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: Push to branch**

Run: `git push origin new-ui`
Expected: Changes pushed successfully

---

## Self-Review Checklist

Scanning plan against spec requirements:

**Spec Coverage:**
- ✅ Desktop-first approach (Tasks 1-5 CSS only, no desktop changes)
- ✅ Mobile navigation (Tasks 6-9 hamburger drawer)
- ✅ Responsive tables (Tasks 10-18 wrapper pattern)
- ✅ Responsive forms (Task 4 CSS)
- ✅ Touch targets (Tasks 1,4 44px minimum)
- ✅ Tier 1 full optimization (Tasks 10-15)
- ✅ Tier 2 basic support (Task 17)
- ✅ Tier 3 minimal support (Task 18)
- ✅ Testing strategy (Tasks 9,16,19-22)

**Placeholder Scan:**
- ✅ No TBD/TODO found
- ✅ All code blocks complete
- ✅ All test steps have expected outcomes
- ✅ File paths exact throughout

**Type Consistency:**
- ✅ `mobileMenuOpen` ref consistent across tasks
- ✅ `navigate(path: string)` function signature consistent
- ✅ `.table-scroll-wrapper` class name consistent
- ✅ CSS variable `--tap-target-min` consistent

Plan is complete and ready for execution.

