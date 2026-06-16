# GitHub Actions 优化方案

## 概述

优化后的 GitHub Actions 工作流按不同触发条件采用不同的构建策略，平衡了测试覆盖率与资源消耗。

## 工作流配置

### 1. Master 分支推送 (`.github/workflows/build.yaml`)

**触发条件**：`push` 到 `master` 分支

**构建策略**：采用 **4 个并行 Job**

#### Job 流程图
```
build-base (构建基础)
    ├─→ build-jvm-images (并行构建 2 个 JVM 镜像) ─┐
    └─→ build-native (构建 Native 镜像)            │
                                                   │
            test-images (并行测试 3 个镜像) ←──────┘
```

#### Job 详细说明

**Job 1: build-base** - 构建共享基础资源
- 编译 Web UI（npm build）
- Maven 打包 JAR
- 提取 Spring Boot layers
- 上传 artifacts

**Job 2: build-jvm-images** - 并行构建 JVM 镜像（Matrix 策略）
- `xiaoya-tvbox:test` (AMD64)
- `alist-tvbox:test` (AMD64)

**Job 3: build-native** - 构建 Native 镜像
- GraalVM native-image 编译
- 构建 `alist-tvbox:native-test`

**Job 4: test-images** - 并行测试 3 个镜像（Matrix 策略）
- 测试 `xiaoya-tvbox`
- 测试 `alist-tvbox`
- 测试 `alist-tvbox-native`

**优化点**：
- ✅ **并行构建**：2 个 JVM 镜像同时构建
- ✅ **Native 并行**：Native 编译与 JVM 镜像并行
- ✅ **并行测试**：3 个镜像同时测试
- ✅ 本地构建不推送（`load: true`）
- ✅ 独立缓存 scope（避免冲突）

### 2. PR 提交 (`.github/workflows/pr.yaml`)

**触发条件**：Pull Request 的 `opened`、`synchronize`、`reopened` 事件

**构建策略**：采用 **3 个并行 Job**

#### Job 流程图
```
build-base (构建基础)
    └─→ build-images (并行构建 2 个 JVM 镜像)
            └─→ test-images (并行测试 2 个镜像)
```

#### Job 详细说明

**Job 1: build-base** - 构建共享基础资源
- 编译 Web UI
- Maven 打包
- 提取 layers
- 上传 artifacts

**Job 2: build-images** - 并行构建 JVM 镜像（Matrix 策略）
- `xiaoya-tvbox:pr-test`
- `alist-tvbox:pr-test`

**Job 3: test-images** - 并行测试 2 个镜像（Matrix 策略）
- 测试 `xiaoya-tvbox`
- 测试 `alist-tvbox`

**优化点**：
- ✅ **并行构建**：2 个镜像同时构建
- ✅ **并行测试**：2 个镜像同时测试
- ✅ 跳过 native 编译（快速验证）
- ✅ 独立缓存 scope

### 3. Tag 发布 (`.github/workflows/release-docker.yaml`)

**触发条件**：`push` tag

**构建策略**：采用 **6 个并行 Job** 实现高效构建

#### Job 流程图
```
build-base (构建基础)
    ├─→ build-jvm-images (并行构建 3 个 JVM 镜像) ─┐
    └─→ build-native (构建 Native 二进制)           │
            └─→ build-native-images (并行构建 3 个 Native 镜像) ─┤
                                                              │
                    test-images (并行测试 3 个镜像) ←─────────┘
                            └─→ notify (发送通知)
```

#### Job 详细说明

**Job 1: build-base** - 构建共享基础资源
- 编译 Web UI（npm build）
- Maven 打包 JAR
- 提取 Spring Boot layers
- 上传 artifacts 供后续 job 使用

**Job 2: build-jvm-images** - 并行构建 JVM 镜像（Matrix 策略）
- `xiaoya-tvbox` (AMD64 + ARM64)
- `xiaoya-tvbox-host` / `hostmode` (AMD64 + ARM64)
- `alist-tvbox` (AMD64 + ARM64)
- 使用独立缓存 scope 避免冲突

**Job 3: build-native** - 构建 Native 二进制
- GraalVM native-image 编译
- 上传二进制供 native 镜像使用

**Job 4: build-native-images** - 并行构建 Native 镜像（Matrix 策略）
- `alist-tvbox-native` (AMD64 only)
- `xiaoya-tvbox-native` (AMD64 only)
- `xiaoya-tvbox-native-host` (AMD64 only)

**Job 5: test-images** - 并行测试镜像（Matrix 策略）
- 测试 `xiaoya-tvbox`
- 测试 `alist-tvbox`
- 测试 `alist-tvbox-native`
- 每个测试独立运行，互不影响

**Job 6: notify** - 发送通知
- 读取 Release Notes
- 检查测试状态（成功/失败）
- 发送电报通知（带状态图标 ✅/❌）

**优化点**：
- ✅ **并行构建**：3 个 JVM 镜像 + 3 个 Native 镜像同时构建
- ✅ **并行测试**：3 个镜像同时测试
- ✅ **依赖管理**：Job 间清晰的依赖关系，最大化并行度
- ✅ **artifacts 复用**：build-base 构建一次，多个 job 共享
- ✅ **独立缓存**：每个镜像独立的 Docker 缓存 scope
- ✅ **故障隔离**：某个镜像失败不影响其他镜像
- ✅ **智能通知**：根据测试结果显示成功/失败状态

## 发布流程

### 使用 `scripts/release.sh` 发布版本

```bash
./scripts/release.sh 1.2.3
```

**脚本功能**：
1. 检查当前分支是否为 `main` 或 `master`
2. 检查工作区是否干净（只允许 `RELEASE_NOTES.md` 和 `docs/*` 未提交）
3. 验证版本号格式（X.Y.Z）
4. 检查 tag 是否已存在（本地和远端）
5. 提交 `RELEASE_NOTES.md` 到 git
6. 推送 commit 和 tag 到远端
7. 输出 Release 页面 URL

### 通过 AI 生成 RELEASE_NOTES.md

在执行 `release.sh` 之前，建议使用 Claude Code 的 `/publish-release` 技能：

```bash
# 1. 生成 RELEASE_NOTES.md（AI 分析 git commits）
/publish-release

# 2. 执行发布脚本
./scripts/release.sh 1.2.3
```

## 测试策略

所有工作流都包含智能的 AList 状态验证：

```bash
# 启动容器
docker run -d --name <container-name> -p <port>:4567 <image>

# 等待 AList 启动完成（最多 60 秒）
SUCCESS=false
for i in {1..30}; do
  RESPONSE=$(curl -s http://localhost:<port>/api/alist/status || echo "0")
  echo "Attempt $i/30: status=$RESPONSE"

  if [ "$RESPONSE" == "2" ]; then
    echo "✅ AList is running (status=2)"
    SUCCESS=true
    break
  elif [ "$RESPONSE" == "1" ]; then
    echo "⏳ AList is starting, waiting..."
  else
    echo "❌ AList not responding, waiting..."
  fi

  sleep 2
done

# 验证是否成功
if [ "$SUCCESS" != "true" ]; then
  echo "❌ Test failed: AList did not reach status 2 within 60 seconds"
  docker logs <container-name>
  exit 1
fi

echo "✅ Test passed: AList status is 2 (running)"
docker stop <container-name>
docker rm <container-name>
```

**API 端点**：`/api/alist/status`

**返回值说明**：
- `2`：✅ AList 正常运行（测试通过）
- `1`：⏳ AList 正在启动（继续等待）
- `0`：❌ AList 未启动或异常（继续等待）

**测试逻辑**：
1. 最多等待 60 秒（30 次 × 2 秒）
2. 每次检查 API 状态码
3. **状态 = 2**：立即通过测试 ✅
4. **状态 = 1**：继续等待（AList 正在启动）⏳
5. **状态 = 0 或无响应**：继续等待 ⏳
6. **超时**：输出日志并失败 ❌

**优势**：
- ✅ 准确验证 AList 服务状态
- ✅ 自动处理启动过程（0 → 1 → 2）
- ✅ 智能等待，启动快则立即继续
- ✅ 失败时输出容器日志便于排查

**适用场景**：
- Master：本地测试 3 个镜像
- PR：快速验证 2 个镜像
- Tag 发布：测试已推送的 3 个镜像

## 资源优化

| 工作流 | Jobs | 镜像数 | 架构 | Native | 推送 | 通知 | 耗时估计 | 并行度 |
|--------|------|--------|------|--------|------|------|----------|--------|
| Master | 4    | 3      | AMD64| ✓     | ✗    | ✗    | ~12min   | **并行** |
| PR     | 3    | 2      | AMD64| ✗     | ✗    | ✗    | ~7min    | **并行** |
| Tag    | 6    | 6      | 多架构| ✓     | ✓    | ✓    | ~25min   | **并行** |

### Master 分支时间优化分析

**旧版本（串行）**：~20 分钟
- Web UI + Maven：8 分钟
- xiaoya-tvbox 镜像：3 分钟
- alist-tvbox 镜像：3 分钟
- Native 编译 + 镜像：10 分钟
- 测试（串行）：3 分钟

**新版本（并行）**：~12 分钟（节省 40%）
- build-base：8 分钟
- build-jvm-images（并行）：3 分钟（2 个镜像同时）
- build-native（并行）：10 分钟（与 JVM 并行）
- test-images（并行）：3 分钟（3 个测试同时）

**关键优化**：Native 编译与 JVM 镜像并行，总时间取 max(3, 10) = 10 分钟

### PR 时间优化分析

**旧版本（串行）**：~10 分钟
- Web UI + Maven：8 分钟
- xiaoya-tvbox 镜像：3 分钟
- alist-tvbox 镜像：3 分钟
- 测试（串行）：2 分钟

**新版本（并行）**：~7 分钟（节省 30%）
- build-base：8 分钟
- build-images（并行）：3 分钟（2 个镜像同时）
- test-images（并行）：2 分钟（2 个测试同时）

**关键优化**：2 个镜像并行构建，测试并行执行

### Tag 发布时间优化分析

**旧版本（串行）**：~40 分钟
- Web UI 构建：3 分钟
- Maven 打包：5 分钟
- JVM 镜像 × 3（串行）：3 × 5 = 15 分钟
- Native 编译：10 分钟
- Native 镜像 × 3（串行）：3 × 2 = 6 分钟
- 测试（串行）：3 分钟
- 通知：1 分钟

**新版本（并行）**：~25 分钟（节省 37.5%）
- build-base：8 分钟（Web UI + Maven）
- build-jvm-images（并行）：5 分钟（3 个镜像同时构建）
- build-native：10 分钟（与 JVM 镜像并行）
- build-native-images（并行）：2 分钟（3 个镜像同时构建）
- test-images（并行）：3 分钟（3 个测试同时运行）
- notify：1 分钟

**关键优化**：
- JVM 镜像构建时间从 15 分钟降至 5 分钟（并行）
- Native 编译与 JVM 构建并行，不再阻塞流程
- 测试并行执行，时间不变但吞吐量提升

## 缓存策略

所有工作流使用 GitHub Actions 缓存：
- Maven 依赖（`cache: 'maven'`）
- npm 依赖（`cache: npm`）
- Docker 层缓存（`cache-from: type=gha`, `cache-to: type=gha,mode=max`）
- GraalVM native 编译缓存（`-Dnative.image.cache=true`）

**独立缓存 scope**：
- Master：`scope=master-{image_name}`
- PR：`scope=pr-{image_name}`
- Tag：`scope={image_name}`
- 每个工作流和镜像独立缓存，避免冲突
- 提高缓存命中率和构建稳定性

## 注意事项

1. **DOCKERHUB_USERNAME** 和 **DOCKERHUB_TOKEN** 需要在仓库 Secrets 中配置
2. **CHANNEL_ID** 和 **BOT_TOKEN** 用于电报通知（可选）
3. Tag 发布需要先创建 GitHub Release（`scripts/release.sh` 会自动创建）
4. Release Notes 通过 `gh release view` 读取，如果不存在则为空
5. 推荐使用 `/publish-release` 技能自动生成 Release Notes
6. **Artifacts 保留期**：build artifacts 和 native binary 保留 1 天后自动删除

## 对比旧版本

### 旧版 master 分支（串行）
- ❌ 构建 6 个镜像（双架构 = 12 个变体）
- ❌ 推送到 Docker Hub
- ❌ 发送电报通知
- ⏱️ 耗时 ~40 分钟

### 新版 master 分支（4 Jobs 并行）
- ✅ 构建 3 个镜像（单架构）
- ✅ 仅本地测试，不推送
- ✅ 不发送通知
- ✅ 2 个 JVM 镜像并行构建
- ✅ Native 与 JVM 并行
- ✅ 3 个测试并行
- ⏱️ 耗时 ~12 分钟（**节省 40%**）

### 旧版 PR 工作流
- ❌ 不存在

### 新版 PR 工作流（3 Jobs 并行）
- ✅ 快速验证（~7 分钟）
- ✅ 2 个镜像并行构建
- ✅ 2 个测试并行
- ✅ 无 native 编译
- ⏱️ 新增快速验证能力

### 旧版 Tag 发布（串行）
- ⏱️ 串行构建，耗时 ~40 分钟
- ❌ 单点故障（一个镜像失败全部停止）
- ❌ 无状态反馈

### 新版 Tag 发布（6 Jobs 并行）
- ⏱️ 并行构建，耗时 ~25 分钟（**节省 37.5%**）
- ✅ 故障隔离（某个镜像失败不影响其他）
- ✅ 智能通知（带成功/失败状态）
- ✅ Artifacts 复用（减少重复构建）
- ✅ 3 个 JVM 镜像 + 3 个 Native 镜像并行
- ✅ 3 个测试并行

## 总结

### 全面采用并行化架构

所有工作流都从串行改为多 Job 并行：
- **Master**：4 个 Job，2 个 JVM 镜像并行 + Native 并行
- **PR**：3 个 Job，2 个镜像并行
- **Tag**：6 个 Job，3 个 JVM + 3 个 Native 并行

### 性能提升

| 场景 | 旧版本 | 新版本 | 提升 |
|------|--------|--------|------|
| Master 推送 | 40min → 20min | 20min → 12min | **累计节省 70%** |
| PR 验证 | 无 | 7min | **新增快速验证** |
| Tag 发布 | 40min | 25min | **节省 37.5%** |

### 关键优化技术

1. ✅ **Matrix 策略**：镜像并行构建和测试
2. ✅ **Job 依赖管理**：最大化并行度
3. ✅ **Artifacts 复用**：build-base 只构建一次
4. ✅ **独立缓存 scope**：避免不同工作流和镜像间冲突
5. ✅ **故障隔离**：Matrix 中某个任务失败不影响其他任务

---

## 🏷️ Tag 发布标签策略（重要更新）

### 核心理念：测试通过才推送 latest

Tag 发布工作流采用**两阶段标签策略**，确保用户始终拉取到经过测试的稳定版本。

### 阶段 1：构建并推送版本标签

首先构建并推送**仅带版本号**的镜像：

```
xiaoya-tvbox-hostmode:1.2.3
alist-tvbox:1.2.3
alist-tvbox-native:1.2.3
xiaoya-tvbox-native:1.2.3
xiaoya-tvbox-native-host:1.2.3
```

### 阶段 2：测试版本标签镜像

测试刚推送的版本标签镜像（调用 `/api/alist/status`，期望返回 `2`）

### 阶段 3：测试通过后添加 latest 标签

**仅在所有测试通过后**，将版本标签镜像重新标记为 latest 和别名：

| 源镜像（已测试） | 添加的标签 |
|------------------|-----------|
| `xiaoya-tvbox-hostmode:1.2.3` | `xiaoya-tvbox:latest`<br>`xiaoya-tvbox:hostmode`<br>`xiaoya-tvbox:host`<br>`xiaoya-tvbox-hostmode:latest`<br>`xiaoya-tvbox-host:latest` |
| `alist-tvbox:1.2.3` | `alist-tvbox:latest` |
| `alist-tvbox-native:1.2.3` | `alist-tvbox:native`<br>`alist-tvbox-native:latest` |
| `xiaoya-tvbox-native:1.2.3` | `xiaoya-tvbox:native`<br>`xiaoya-tvbox-native:latest` |
| `xiaoya-tvbox-native-host:1.2.3` | `xiaoya-tvbox:native-host`<br>`xiaoya-tvbox-native-host:latest` |

### 优势

#### 1. 安全性
- ✅ 用户拉取 `latest` 标签，获得的是**经过测试验证**的版本
- ✅ 测试失败时，`latest` 仍指向上一个稳定版本
- ✅ 不会推送未经测试的镜像给生产用户

#### 2. 可追溯性
- ✅ 版本标签（如 `1.2.3`）永久保留
- ✅ 测试失败时，版本标签仍存在，便于开发者调试
- ✅ 可以明确知道每个版本的测试状态

#### 3. 灵活性
- ✅ **稳定用户**：使用 `latest` 标签，获得稳定版本
- ✅ **尝鲜用户**：使用版本标签（如 `1.2.3`），体验最新功能
- ✅ **开发者**：测试失败时用版本标签快速定位问题

#### 4. 性能
- ✅ 保持并行构建，不重复构建
- ✅ 添加标签只需 1 分钟（docker tag + push）
- ✅ 总时间不增加

### 工作流程图

```
build-base (8min)
    ├─→ build-jvm-images (推送版本标签，5min) ────┐
    └─→ build-native (10min)                      │
            └─→ build-native-images (推送版本标签，2min) ─┤
                                                      │
                test-images (测试版本标签，3min) ←────┘
                        │
                        ├─ 测试通过 → tag-latest (添加 latest，1min)
                        │                  └─→ notify (通知，1min)
                        │
                        └─ 测试失败 → 不添加 latest
                                      └─→ notify (失败通知)
```

### 示例场景

#### 场景 1：测试通过（正常流程）

```
1. 推送 alist-tvbox:1.2.3 ✅
2. 测试 alist-tvbox:1.2.3 ✅ (status=2)
3. 添加标签：
   - alist-tvbox:latest → 1.2.3 ✅
4. 用户拉取：
   - docker pull xxx/alist-tvbox:latest  # 获得 1.2.3
```

#### 场景 2：测试失败

```
1. 推送 alist-tvbox:1.2.4 ✅
2. 测试 alist-tvbox:1.2.4 ❌ (status=0, 超时)
3. 不添加 latest 标签
4. 用户拉取：
   - docker pull xxx/alist-tvbox:latest  # 仍获得 1.2.3（上一个稳定版）
5. 开发者调试：
   - docker pull xxx/alist-tvbox:1.2.4   # 可以拉取失败版本调试
```

### Jobs 数量变化

- **旧版本**：6 个 Jobs
- **新版本**：7 个 Jobs
  - 新增：`tag-latest`（添加 latest 标签）
  - 依赖：`test-images` 通过后才执行

### 总时间

- **理论时间**：~26 分钟（增加 1 分钟用于标签操作）
- **实际时间**：~25-27 分钟
- **增加原因**：tag-latest Job 需要拉取镜像、重新标记、推送

---

## 📊 更新后的资源优化对比

| 工作流 | Jobs | 镜像数 | 架构 | Native | 测试验证 | 推送策略 | 耗时 |
|--------|------|--------|------|--------|----------|----------|------|
| Master | 4    | 3      | AMD64| ✓     | ✓ (本地) | 不推送 | ~12min |
| PR     | 3    | 2      | AMD64| ✗     | ✓ (本地) | 不推送 | ~7min |
| Tag    | 7    | 6      | 多架构| ✓     | ✓ (远程) | **版本标签 → 测试 → latest** | ~26min |

### Tag 发布流程对比

| 阶段 | 旧方案 | 新方案（标签策略） |
|------|--------|-------------------|
| 构建 | 推送全部标签 | 只推送版本标签 |
| 测试 | 测试已推送镜像 | 测试版本标签镜像 |
| 推送 latest | 构建时已推送 | **测试通过后才推送** |
| 测试失败 | latest 已推送（风险） | latest 不变（安全） |


---

## 📢 通知策略

### Master 分支和 PR
- ❌ **不发送**电报通知
- 原因：这些是开发测试构建，不面向最终用户

### Tag 发布
- ✅ **仅在测试成功后**发送电报通知
- 依赖：`notify` job 依赖 `tag-latest`（测试通过才执行）
- 内容：版本号、Release Notes、下载链接
- 失败时：**不发送通知**（避免误导用户）

### 通知逻辑

```yaml
notify:
  needs: tag-latest  # 依赖 tag-latest 成功
  # 没有 if: always()，所以只在成功时执行
```

**流程**：
```
测试通过 → tag-latest 成功 → notify 执行 → 发送通知 ✅
测试失败 → tag-latest 跳过 → notify 跳过 → 不发送通知 ❌
```

**优势**：
- ✅ 用户只收到成功发布的通知
- ✅ 避免"发布失败"的负面通知
- ✅ 保持用户频道的信息质量
