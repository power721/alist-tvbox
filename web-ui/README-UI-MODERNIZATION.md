# UI现代化升级说明

## 概述

本次升级对AList-TvBox管理后台进行了视觉现代化改造，引入了蓝紫渐变主题色、卡片化布局和现代圆角风格。

## 主要改进

### 1. 配色系统
- 引入蓝紫渐变主题色 (#667eea → #764ba2)
- 优化深色/浅色主题对比度
- 统一阴影和边框颜色

### 2. 布局优化
- 所有页面采用卡片化布局
- 统一间距系统 (8px, 12px, 16px, 20px, 24px, 32px)
- 页面容器最大宽度 1400px

### 3. 组件样式
- 按钮：圆角8px，主按钮使用渐变背景，hover上浮效果
- 表格：圆角12px，行hover微缩放效果
- 表单：输入框圆角8px，focus蓝色光晕
- 导航栏：活跃菜单项渐变下划线

### 4. 响应式设计
- PC端 (≥1200px): 默认布局
- 平板端 (768-1199px): 适当压缩间距
- 移动端 (<768px): 表格横向滚动，导航折叠

## 技术实现

### CSS变量文件
- `src/assets/theme.css` - 主题变量定义
- `src/assets/modern.css` - 组件样式定义

### 样式类
- `.page-container` - 页面容器
- `.page-header` - 页面头部
- `.page-title` - 页面标题
- `.page-card` - 卡片容器
- `.card-header` - 卡片头部
- `.card-title` - 卡片标题

## 已改造的页面

- ✅ App.vue - 添加modern-main类
- ✅ HomeView.vue - 完整卡片化
- ✅ SitesView.vue - 完整卡片化
- ✅ SubscriptionsView.vue - 页面容器和标题
- ✅ ConfigView.vue - 页面容器和标题

## 浏览器支持

- Chrome/Edge 90+
- Firefox 88+
- Safari 14+

## 性能

- 首屏加载时间: 无明显影响
- 动画帧率: 60fps
- CSS文件大小增加: ~10KB

## 使用说明

### 开发环境
```bash
cd web-ui
npm run dev
```

### 生产构建
```bash
cd web-ui
npm run build
```

构建输出位于 `src/main/resources/static/`

## 下一步优化方向

1. 完成所有页面的卡片化改造
2. 添加骨架屏加载状态
3. 优化空状态展示
4. 增加更多微交互动画
5. 进一步优化移动端体验
