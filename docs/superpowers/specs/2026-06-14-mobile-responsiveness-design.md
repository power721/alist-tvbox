# Mobile Responsiveness Design

**Date:** 2026-06-14  
**Status:** Approved  
**Approach:** Progressive Enhancement (CSS-first)

## Overview

Add mobile browser compatibility to AList-TvBox web UI while maintaining desktop-first UX. Focus on most-used admin workflows (subscriptions, sites, accounts) being fully functional on mobile, with advanced admin features remaining desktop-recommended.

## Requirements

### Primary
- Desktop browsers remain primary target — no regressions
- Existing desktop layouts, interactions unchanged
- Mobile browsers (320px-768px) functional for priority workflows
- No horizontal page-level scrolling on mobile
- Touch interactions work correctly

### Browser Support
- **Desktop:** Chrome, Edge, Firefox, Safari
- **Mobile:** Chrome (Android), Safari (iOS)

## Architecture

### Approach: Progressive Enhancement

CSS-first responsive patterns layered on existing component structure. No new Vue components, no routing changes. Responsive behavior implemented through:

1. **Enhanced media queries** in `modern.css`
2. **Navigation adaptation** in `App.vue` (hamburger menu via `el-drawer`)
3. **Table patterns** using Element Plus responsive features
4. **Touch optimizations** via CSS

### Breakpoints

- **320px:** Minimum mobile support
- **768px:** Tablet/mobile boundary (existing in `modern.css`)
- **1200px:** Desktop (existing in `modern.css`)

Aligns with Element Plus defaults and existing codebase patterns.

## Component Strategy

### Navigation (App.vue)

**Desktop (<768px and above):**
- Horizontal menu unchanged
- All current behavior preserved

**Mobile (<768px):**
- Horizontal menu hidden
- Hamburger icon (☰) triggers `el-drawer` from left
- Drawer contains full menu items vertically stacked
- User menu (username dropdown) accessible in drawer
- "高级模式/简单模式" switch moves to drawer footer

**Implementation:**
- Add `el-drawer` component with `v-model` bound to `mobileMenuOpen` ref
- Show/hide trigger button via CSS media query
- Menu items remain same component, rendered inside drawer on mobile

### Tables (All Views)

**Desktop:**
- Current table behavior unchanged
- No modifications to existing Element Plus table props

**Mobile (<768px):**
- Wrap tables in `.table-scroll-wrapper` div
- Enable horizontal scroll with `-webkit-overflow-scrolling: touch`
- First column sticky (position: sticky, left: 0) for context while scrolling
- Action column visible, buttons reduced to icons where text is redundant
- Table minimum width set to prevent column crushing

**Implementation:**
- Add wrapper div around `<el-table>` in each view
- CSS handles scrolling + sticky column behavior
- No changes to table data or props

### Forms (Dialogs/Modals)

**Desktop:**
- Current modal widths unchanged
- Form layout as-is

**Mobile (<768px):**
- Dialogs expand to 95vw width
- Form labels/inputs stack vertically (flex-direction: column)
- Input fields full-width
- Larger touch targets for switches, checkboxes, radio buttons
- Element Plus handles keyboard covering inputs automatically

**Implementation:**
- Media query overrides for `.el-dialog` width
- `.el-form-item` flex-direction change
- Increased min-height for form controls

### Page Structure

**page-header:**
- Desktop: flex-row, space-between (current)
- Mobile: flex-column, align-start, gap 16px (partially exists in modern.css line 257-261)

**page-actions:**
- Desktop: flex-row, gap (current)
- Mobile: flex-wrap, buttons stack to multiple rows

**page-card:**
- Desktop: padding 24px (current)
- Mobile: padding 16px, border-radius 8px (exists in modern.css line 252-255)

**page-container:**
- Desktop: padding 24px (current)
- Mobile: padding 16px (exists in modern.css line 249)

## Priority Tiers

Views organized by mobile optimization level:

### Tier 1: Full Mobile Optimization
Most-used admin workflows — complete mobile UX.

- **SubscriptionsView** (订阅管理) — add/edit/delete subscriptions, view configs
- **SitesView** (站点列表) — manage AList sites
- **AccountsView** (账号管理) — cloud drive accounts
- **SearchView** (搜索) — content search
- **VodView** (播放) — VOD playback
- **LiveView** (直播) — live streaming

**Work:** Full table wrapping, optimized forms, tested workflows end-to-end.

### Tier 2: Mobile-Usable
Secondary admin — functional but not optimized.

- **SharesView** (资源) — resource management
- **ConfigView** (配置) — settings
- **LogsView** (日志) — log viewing
- **UsersView** (用户) — user management
- **HomeView** (首页) — dashboard

**Work:** Table wrapping, basic form stacking, acceptable UX.

### Tier 3: Desktop-Recommended
Advanced admin — minimal mobile support, desktop preferred.

- **IndexView** (索引), **AclView** (ACL), **AliasView** (别名), **FilesView** (文件)
- **BiliBiliView**, **EmbyView**, **JellyfinView**, **FeiniuView**
- **MetaView**, **TmdbView**, **PikPakView**, **DriverAccountView**

**Work:** Basic responsive patterns applied (no page overflow), but no workflow optimization. These remain desktop tools.

## CSS Organization

All responsive styles added to `web-ui/src/assets/modern.css`, extending existing media queries at lines 232-266.

### New Utility Classes

```css
/* Table horizontal scroll wrapper */
.table-scroll-wrapper {
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  margin: 0 -16px; /* bleed to card edges on mobile */
  padding: 0 16px;
}

/* Mobile-specific utility */
.mobile-only {
  display: none;
}

@media (max-width: 767px) {
  .mobile-only {
    display: block;
  }
  
  .desktop-only {
    display: none;
  }
}
```

### CSS Variables

Add touch-target sizing to `theme.css`:

```css
:root {
  --tap-target-min: 44px; /* iOS Human Interface Guidelines */
}
```

## Key Implementation Patterns

### Pattern 1: Responsive Tables

**Before:**
```vue
<el-table :data="items" border>
  <!-- columns -->
</el-table>
```

**After:**
```vue
<div class="table-scroll-wrapper">
  <el-table :data="items" border style="min-width: 800px">
    <!-- columns unchanged -->
  </el-table>
</div>
```

**CSS:**
```css
@media (max-width: 767px) {
  .table-scroll-wrapper {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }
  
  /* Sticky first column for context */
  .el-table th:first-child,
  .el-table td:first-child {
    position: sticky;
    left: 0;
    background: var(--bg-card);
    z-index: 2;
    box-shadow: 2px 0 4px rgba(0,0,0,0.05);
  }
  
  /* Ensure action column visible */
  .el-table [fixed="right"] {
    position: sticky;
    right: 0;
    background: var(--bg-card);
  }
}
```

### Pattern 2: Mobile Navigation

**App.vue additions:**

```vue
<template>
  <el-header>
    <!-- Desktop menu (existing) -->
    <el-menu mode="horizontal" class="desktop-nav">
      <!-- existing menu items -->
    </el-menu>
    
    <!-- Mobile trigger -->
    <div class="mobile-nav-header">
      <h2 class="app-title">AList-TvBox</h2>
      <el-button class="mobile-menu-trigger" @click="mobileMenuOpen = true">
        <el-icon><Menu /></el-icon>
      </el-button>
    </div>
    
    <!-- Mobile drawer -->
    <el-drawer v-model="mobileMenuOpen" direction="ltr" size="80%">
      <template #header>
        <span>菜单</span>
      </template>
      <el-menu>
        <!-- Same menu items as desktop, vertical layout -->
        <el-menu-item index="/" v-if="store.admin" @click="navigate('/')">首页</el-menu-item>
        <!-- ... rest of menu items ... -->
      </el-menu>
      <template #footer>
        <el-switch v-model="full" inline-prompt active-text="高级" inactive-text="简单" />
      </template>
    </el-drawer>
  </el-header>
</template>

<script setup>
const mobileMenuOpen = ref(false)

const navigate = (path: string) => {
  router.push(path)
  mobileMenuOpen.value = false
}
</script>

<style>
@media (min-width: 768px) {
  .mobile-nav-header,
  .mobile-menu-trigger {
    display: none;
  }
}

@media (max-width: 767px) {
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
  
  .mobile-menu-trigger {
    min-width: 44px;
    min-height: 44px;
  }
}
</style>
```

### Pattern 3: Responsive Forms

**CSS additions to modern.css:**

```css
@media (max-width: 767px) {
  /* Full-width dialogs */
  .el-dialog {
    width: 95vw !important;
    margin: 5px auto !important;
  }
  
  /* Stack form labels/inputs */
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
  
  /* Full-width inputs */
  .el-input,
  .el-select,
  .el-textarea {
    width: 100%;
  }
  
  /* Larger touch targets */
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
}
```

### Pattern 4: Touch-Friendly Buttons

**CSS additions:**

```css
@media (max-width: 767px) {
  /* Wrap action buttons */
  .page-actions {
    flex-wrap: wrap;
    gap: 8px;
  }
  
  /* Icon-only buttons for tables */
  .el-table .el-button--small span {
    /* Text hidden on mobile, icon remains */
  }
  
  /* Ensure tap targets */
  .el-table .el-button--small {
    min-width: 44px;
    min-height: 44px;
    padding: 10px;
  }
}
```

## Testing Strategy

### Browser Device Emulation

Use Chrome DevTools device toolbar with these profiles:
- **iPhone SE** (375x667) — minimum iOS
- **iPhone 12 Pro** (390x844) — modern iOS
- **iPad** (768x1024) — tablet boundary
- **Pixel 5** (393x851) — modern Android
- **Samsung Galaxy S20** (360x800) — compact Android

Test at exact breakpoints: 320px, 375px, 414px, 768px, 1024px (both portrait and landscape).

### Manual Testing Targets

**Mobile:**
- iOS Safari (iPhone 12 or newer)
- Chrome Android (Pixel or Samsung)

**Desktop (regression check):**
- Chrome 120+
- Firefox 120+
- Edge 120+
- Safari 17+

### Test Scenarios by Tier

**Tier 1 Views (Full workflow):**
1. Navigate to view via mobile menu
2. Load table, verify horizontal scroll + sticky first column
3. Open add/edit dialog, fill form, submit
4. Verify success feedback
5. Delete item, confirm action
6. Refresh, verify persistence

**Tier 2 Views (Basic interaction):**
1. Navigate to view
2. Scroll table
3. Open dialog, verify layout
4. Close without changes

**Tier 3 Views (No breakage):**
1. Navigate to view
2. Verify no horizontal page scroll
3. Verify buttons accessible
4. No functional testing required

### Verification Checklist

- [ ] No horizontal scroll at page level on any view
- [ ] All buttons/links meet 44px minimum tap target
- [ ] Forms submit successfully on mobile
- [ ] Dialogs open, scroll, close correctly on mobile
- [ ] Tables scroll horizontally with sticky first column
- [ ] Navigation accessible via hamburger menu
- [ ] User menu (logout, settings) accessible on mobile
- [ ] Desktop Chrome unchanged (spot-check 5 views)
- [ ] Desktop Firefox unchanged (spot-check 3 views)
- [ ] Desktop Safari unchanged (spot-check 3 views)

### Implementation Phases

Phase work sequentially, verify each before continuing:

1. **Navigation** — App.vue hamburger menu, test on 3 views
2. **Tables** — Add wrappers + CSS, test Tier 1 views
3. **Forms** — Dialog/form CSS, test add/edit flows in Tier 1
4. **Touch optimization** — Button sizing, spacing refinements
5. **Tier 2/3** — Apply patterns to remaining views
6. **Final validation** — Full checklist on real devices

Use browser emulation for rapid iteration (steps 1-5), manual device testing for final validation (step 6).

## Non-Goals

Explicitly out of scope:

- **No mobile-first redesign** — desktop UX unchanged
- **No separate mobile components** — one codebase, responsive CSS only
- **No touch gestures** beyond standard (tap, scroll) — no swipe actions
- **No mobile-specific features** — feature parity with desktop
- **No PWA** — remains standard web app
- **No offline support** — requires network as on desktop
- **No native app wrappers** — browser-only

## Risks & Mitigations

**Risk:** Element Plus table horizontal scroll breaks sticky columns on iOS Safari.  
**Mitigation:** Test early on real iOS device. Fallback: drop sticky columns on iOS if unfixable.

**Risk:** Desktop regression — CSS changes affect desktop layouts.  
**Mitigation:** All mobile CSS inside `@media (max-width: 767px)` blocks. Desktop explicitly tested.

**Risk:** Large tables (100+ rows) perform poorly on mobile.  
**Mitigation:** Existing pagination remains. Mobile adds no performance overhead.

**Risk:** Complex forms (SubscriptionConfigEditor) unusable on small screens.  
**Mitigation:** Full-width inputs + vertical stacking. If still unusable, add "desktop recommended" notice to that specific dialog.

## Success Criteria

1. **No horizontal page scroll** on any view at 320px-768px widths
2. **Tier 1 workflows functional** — user can complete add/edit/delete on mobile without frustration
3. **Zero desktop regressions** — spot-check tests pass on Chrome/Firefox/Safari desktop
4. **44px tap targets** — all interactive elements meet iOS standard
5. **Manual device validation** — iOS Safari and Chrome Android tested, no critical issues

## Future Enhancements

Post-initial implementation, potential improvements:

- **Virtualized tables** for large datasets on mobile (vue-virtual-scroller)
- **Bottom sheets** for mobile dialogs (more native feel than centered modals)
- **Pull-to-refresh** on table views
- **Touch gestures** (swipe to delete) in Tier 1 views
- **PWA manifest** for "add to home screen" capability
- **Responsive images** in media-heavy views (VodView, SearchView)

These deferred to keep initial scope focused and reduce risk.
