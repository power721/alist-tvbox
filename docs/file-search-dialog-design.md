# 文件浏览页 - 文件搜索框 (File Search)

Date: 2026-06-27
Scope: `web-ui/src/views/VodView.vue` only. No backend change.

## Goal
文件浏览页 (`VodView.vue`) 电报搜索框右侧新增一个**内联文件搜索框**，调用
`GET /vod/{token}?ac=gui&wd=<kw>` 跨已配置站点搜索文件/文件夹，结果复用现有
左侧结果栏。点击结果跳转到其**所在目录**。

## API behavior (verified)
- `TvBoxService.search(type=1, ac=gui, wd, pg)` 并发聚合所有可搜索站点
  (`searchByFile` + `searchByApi`)，去重后截断至 `maxSearchResult`，一次性返回。
- 响应 `MovieList`: `page/pagecount(=1)/total/list`。**无服务端分页**，单请求平铺列表。
- list 元素即 `MovieDetail`，字段与 `VodItem` 完全对应，无需映射。
- `vod_id` 格式 `siteId$pathProxy$type` (如 `40$swz4wm23wsp-...$1`)。

## UI
- 头部 flex 容器内，电报输入框后、操作按钮前，新增同级 `<el-input>`
  (`v-model="fileKeyword"`, placeholder `搜索文件资源`, `@keyup.enter/@click="searchFiles"`)，
  样式与电报框一致 (width 300, append 搜索按钮)。

## 共用结果栏 (v-if results.length)
- 新增 `searchMode` ref: `'tg' | 'file'`。两路搜索都写入 `results`/`filteredResults`。
- 内容列: tg 模式保留 `getShareType(type_name)` + name；**file 模式**显示
  `vod_tag`/`type` 图标 (📂🎬🎧…) + name + `vod_remarks`(大小)。
- 头部: tg 类型筛选下拉仅 tg 模式显示；`清除` 始终显示。无分页。
- `@row-click`: tg → `loadResult`; file → `loadFileResult`。

## 点击行为 (file 模式 → 跳转所在目录)
搜索结果只有 `vod_id`，无 slash path，故:
```
GET /vod/{token}?ac=web&ids={vod_id}  ->  data.list[0].path (slash path)
-> goParent(path) -> loadFolder(parent)   // 复用现有函数
```
folder/file 均跳到其所在目录。

## New refs / functions
- refs: `fileKeyword`, `fileSearching`, `searchMode`
- `searchFiles()`: set searching; GET ac=gui&wd; `results = data.list`; searchMode='file'.
- `loadFileResult(row)`: GET ac=web&ids=row.vod_id -> goParent(path).
- `clearSearch()`: 增加 reset `searchMode`。
