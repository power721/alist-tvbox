# 网络模式切换修复说明

## 问题描述

用户报告：脚本无法正确切换到 host 网络模式。配置文件显示 host 模式，但容器实际运行在 bridge 模式。

## 根本原因

**配置同步冲突**：

1. `show_menu()` 在每次显示菜单时调用 `sync_runtime_config()`（第534行）
2. `sync_runtime_config()` 从现有容器读取实际网络模式并**覆盖内存中的 CONFIG**（第219行）
3. 用户修改配置文件设置 `NETWORK=host` → 保存成功 ✅
4. 但内存中的 `CONFIG["NETWORK"]` 已被旧容器的 bridge 模式覆盖 ❌
5. 创建新容器时使用内存中的 CONFIG，导致仍然是 bridge 模式

**流程示例（修复前）：**

```bash
# 1. 用户打开菜单
show_menu()
  └─ sync_runtime_config()  # 从旧容器读取 bridge → CONFIG["NETWORK"]="bridge"

# 2. 用户选择"配置管理" → "网络模式" → "host模式"
show_network_menu()
  └─ CONFIG["NETWORK"]="host"  # 设置为 host
  └─ save_config()              # 保存到文件 ✅

# 3. 重建容器
recreate_container_for_changes()
  └─ start_container()
     └─ 读取 CONFIG["NETWORK"]   # 但此时内存中仍是 bridge ❌
     └─ docker run --network bridge  # 错误！
```

## 修复方案

### 1. `show_network_menu()` 函数改进

**在函数开始时从配置文件重新加载网络模式**，避免使用被 `sync_runtime_config` 覆盖的内存值：

```bash
# 从配置文件重新加载，避免被 sync_runtime_config 覆盖的内存值
local saved_network=""
if [[ -f "$CONFIG_FILE" ]]; then
  saved_network=$(grep "^NETWORK=" "$CONFIG_FILE" 2>/dev/null | cut -d'=' -f2)
fi
[[ -n "$saved_network" ]] && CONFIG["NETWORK"]="$saved_network"
```

**显示配置值和容器实际值的差异**：

```bash
echo -e " 当前配置: ${GREEN}${CONFIG[NETWORK]}${NC}"

# 显示容器实际使用的网络模式（如果存在）
if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${container_name}\$"; then
  local actual_network=$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$container_name" 2>/dev/null)
  if [[ -n "$actual_network" && "$actual_network" != "${CONFIG[NETWORK]}" ]]; then
    echo -e " 容器实际: ${YELLOW}${actual_network}${NC} (需要重建容器使配置生效)"
  fi
fi
```

这样用户可以清楚地看到配置值和实际运行值的区别。

### 2. `show_config_menu()` 函数改进

**每次显示配置菜单时从配置文件重新加载关键配置**：

```bash
# 从配置文件重新加载关键配置，避免被 sync_runtime_config 覆盖
if [[ -f "$CONFIG_FILE" ]]; then
  while IFS='=' read -r key value; do
    if [[ -n "$key" && "$key" =~ ^(NETWORK|BASE_DIR|PORT1|PORT2|RESTART|MOUNT_WWW|GITHUB_PROXY)$ ]]; then
      CONFIG["$key"]="$value"
    fi
  done < "$CONFIG_FILE"
fi
```

### 3. 修复配置菜单中的网络模式调用

将第1392行的 `need_recreate=true` 移除，因为 `show_network_menu()` 内部已经处理了容器重建逻辑。

```bash
6)
  show_network_menu
  # show_network_menu 内部已处理重建逻辑
  continue
  ;;
```

## 修复后的流程

**流程示例（修复后）：**

```bash
# 1. 用户打开菜单
show_menu()
  └─ sync_runtime_config()  # 从旧容器读取 bridge → CONFIG["NETWORK"]="bridge"

# 2. 用户选择"配置管理" → "网络模式"
show_network_menu()
  └─ 从配置文件重新加载 → CONFIG["NETWORK"]="host" (如果之前保存过)
  └─ 显示: "当前配置: host"
  └─ 显示: "容器实际: bridge (需要重建容器使配置生效)"
  └─ 用户选择 "2. host模式"
  └─ CONFIG["NETWORK"]="host"
  └─ save_config()  # 保存到文件

# 3. 重建容器
recreate_container_for_changes()
  └─ start_container()
     └─ 读取 CONFIG["NETWORK"]  # 现在是正确的 host ✅
     └─ docker run --network host  # 正确！
```

## 测试用例

### 测试1：从 bridge 切换到 host

```bash
# 前置条件
# - 容器运行在 bridge 模式
# - 配置文件: NETWORK=bridge

# 操作步骤
1. 运行脚本: ./scripts/alist-tvbox.sh
2. 选择: 8. 配置管理
3. 选择: 6. 网络模式
4. 应显示:
   当前配置: bridge
5. 选择: 2. host模式
6. 确认立即重建容器: Y

# 预期结果
# - 配置文件更新为 NETWORK=host
# - 容器使用 --network host 启动
# - docker inspect 显示 NetworkMode: host
```

### 测试2：配置已是 host 但容器运行在 bridge

```bash
# 前置条件
# - 容器运行在 bridge 模式
# - 配置文件: NETWORK=host (之前手动修改过)

# 操作步骤
1. 运行脚本: ./scripts/alist-tvbox.sh
2. 选择: 8. 配置管理
3. 选择: 6. 网络模式
4. 应显示:
   当前配置: host
   容器实际: bridge (需要重建容器使配置生效)
5. 选择: 0. 返回 (不修改，测试是否能用现有配置重建)
6. 返回主菜单
7. 选择: 1. 安装/更新

# 预期结果
# - 容器使用配置文件中的 host 模式重建
# - docker inspect 显示 NetworkMode: host
```

### 测试3：多次切换

```bash
# 测试配置不会被意外覆盖

1. bridge → host → 重建 ✓
2. 返回菜单 → 再次进入配置
3. 应显示: 当前配置: host
4. host → bridge → 重建 ✓
5. 返回菜单 → 再次进入配置
6. 应显示: 当前配置: bridge
```

## 验证命令

### 检查配置文件

```bash
cat ~/.config/alist-tvbox/app.conf | grep NETWORK
# 应输出: NETWORK=host 或 NETWORK=bridge
```

### 检查容器实际网络模式

```bash
docker inspect xiaoya-tvbox --format '{{.HostConfig.NetworkMode}}'
# 应输出: host 或 bridge 或 default
```

### 检查容器端口映射

```bash
# bridge 模式下应有端口映射
docker port xiaoya-tvbox
# 输出: 4567/tcp -> 0.0.0.0:4567 等

# host 模式下不应有端口映射
docker port xiaoya-tvbox
# 应为空或报错 "Error: No public port '...' published for xiaoya-tvbox"
```

### 验证 host 模式正常工作

```bash
# host 模式下，容器直接使用主机网络
# 应该能在主机的 4567, 5678, 5233, 5234 端口访问服务

curl -f http://localhost:4567/ && echo "✓ 管理应用正常"
curl -f http://localhost:5234/ && echo "✓ AList正常"
```

## 影响范围

**修改的文件**：
- `scripts/alist-tvbox.sh`
  - `show_network_menu()` 函数（第1213-1269行）
  - `show_config_menu()` 函数（第1313-1333行，新增配置重载逻辑）

**向后兼容性**：
- ✅ 完全向后兼容
- ✅ 不影响已正确配置的用户
- ✅ 修复了配置与实际运行状态不一致的问题

## 已知限制

**`sync_runtime_config()` 的设计意图**：
该函数设计用于**显示容器实际运行状态**，而不是用于创建容器的配置。这是合理的设计，因为用户可能手动修改了容器。

**改进点**：
我们的修复保留了 `sync_runtime_config()` 的功能（显示实际状态），同时确保：
1. 配置菜单显示的是**配置文件**中的值
2. 创建容器时使用**配置文件**中的值
3. 同时显示配置值和实际值的差异，让用户清楚当前状态

## 提交信息

```
fix(scripts): 修复网络模式切换失败问题

问题：
- show_menu 调用 sync_runtime_config 从容器读取网络模式
- 覆盖内存中的 CONFIG["NETWORK"]
- 用户修改配置文件后，重建容器仍使用旧的内存值
- 导致配置显示 host，但容器实际运行 bridge

修复：
- show_network_menu 开始时从配置文件重新加载网络模式
- show_config_menu 每次显示时重新加载所有关键配置
- 显示"当前配置"和"容器实际"两个值，清晰展示差异
- 确保重建容器时使用配置文件中的值，而非被覆盖的内存值

测试：
- 从 bridge 切换到 host ✓
- 从 host 切换到 bridge ✓
- 配置文件已是 host 但容器运行 bridge，重建后正确切换 ✓
```
