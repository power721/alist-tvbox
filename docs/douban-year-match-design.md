# 豆瓣匹配加入「年代最近匹配」

## 背景
`TvBoxService.setMovieInfo`（刮削路径）调用 `DoubanService.getByName(name)` 做豆瓣数据库匹配。该方法为精确名称匹配，多候选时取 `get(0)`，**不使用年代**。导致同名作品或文件名≠豆瓣名时错配，刮削失败。

## 目标
用「名称 + 年代」做最佳匹配：从标题/路径提取年代，精确名多候选时取年代最近；精确名无果时用「年代 + 名称包含」回退挑最佳。

## 现状可复用件
- `getYearFromPath(path)`：按路径段提取年代（范围 1961–now+2）。
- `movieRepository.findByYearAndNameContains(year, name)`：年代相等 + 名称包含。
- `Movie.year`（`Integer`，可空）。
- `YEAR_PATTERN` = `\((\d{4})\)`、`YEAR2_PATTERN` = `(\d{4})`。

## 改动

### 1. `DoubanService.getYear(String name, String path)`（新增）
- 先 `getYearFromPath(path)`；为空则扫 `name`：优先 `YEAR_PATTERN`（括号年），再 `YEAR2_PATTERN` 裸 4 位，均套用 1961–now+2 范围；返回首个有效年或 null。

### 2. `DoubanService.getByName(String name, Integer year)`（新增重载）
1. 别名 `alias` 查找（原样 + `fixName`）→ 命中返回（年代不适用）。
2. `movieRepository.getByName(name)` 精确名 → `pickBest(candidates, year)`：
   - 多候选且 year≠null → `min |cand.year - year|`（cand.year 为 null 排后；并列取较小年）。
   - 否则 → 第一个（现状）。
3. `updateName` 变体同上。
4. 新增回退：仍无果且 year≠null → `findByYearAndNameContains(year, name)` → `pickBestName(results, name)`（精确相等 > 名称最短 > 第一个）。
- 旧 `getByName(String name)` 委托 `getByName(name, null)`，其它调用方（行 406、673）不变。

### 3. `TvBoxService.setMovieInfo`
- 顶部（TMDB 早返回之后）提取一次 `year = doubanService.getYear(movieDetail.getVod_name(), path)`。
- 5 处 `doubanService.getByName(变体)` → `doubanService.getByName(变体, year)`。

## 兼容性
- year=null 时与今天完全一致。
- 别名路径不变；其它 `getByName` 调用方不动。

## 测试
新建 `DoubanServiceTest`：精确名多候选→取最近年；精确名无果+有年→contains 回退挑最佳；year=null→取第一个。`pickBest`/`pickBestName` 设为包级可见以便单测。
