# Host 网络模式兼容性修复

## 问题描述

普通镜像（如 `haroldli/xiaoya-tvbox`）的 AList 服务监听 80 端口。当使用 host 网络模式时，会直接占用主机的 80 端口，导致端口冲突。

只有 `:host` 变体镜像（如 `haroldli/xiaoya-tvbox:host`）才针对 host 网络优化，不会占用 80 端口。

## 解决方案

### 1. 新增兼容性验证函数

```bash
validate_image_network_compatibility()
```

该函数执行双向验证：
- **host 镜像 → 必须使用 host 网络**（匹配 `:host` 或 `-host` 结尾）
- **host 网络 → 必须使用 host 镜像**

匹配规则使用正则表达式：`(:|-)host$`，可识别：
- `haroldli/xiaoya-tvbox:native-host` ✓
- `haroldli/xiaoya-tvbox:host` ✓

### 2. 在关键路径添加验证

- **安装/更新容器时** (`install_container`)
  - 自动将 `:host` 镜像切换到 host 网络
  - 验证兼容性，失败则终止安装

- **切换版本时** (`show_version_menu`)
  - 自动调整网络模式（`:host` → host）
  - 验证兼容性，失败则回滚版本选择

- **修改网络模式时** (`show_network_menu`)
  - 验证与当前镜像的兼容性
  - 失败则回退到原网络模式

## 错误提示

### host 镜像 + bridge 网络
```
错误: haroldli/xiaoya-tvbox:host 必须使用 host 网络模式
该镜像专为 host 网络优化，不支持端口映射
```

### host 网络 + 普通镜像
```
错误: host 网络模式必须使用 :host 镜像版本
普通镜像的 AList 监听 80 端口，会占用主机端口
请选择版本 6 (native-host) 或版本 7 (host)
```

## 版本对照

| 版本号 | 镜像名称 | 支持的网络模式 |
|-------|---------|--------------|
| 1-5, 8-9 | 普通镜像 | bridge only |
| 6 | `xiaoya-tvbox:native-host` | **host only** |
| 7 | `xiaoya-tvbox:host` | **host only** |

**识别规则**：镜像名以 `:host` 或 `-host` 结尾的为 host 专用镜像。

## 使用示例

### 正确配置
```bash
# host 镜像 + host 网络 ✓
IMAGE_NAME=haroldli/xiaoya-tvbox:host
NETWORK=host

# native-host 镜像 + host 网络 ✓
IMAGE_NAME=haroldli/xiaoya-tvbox:native-host
NETWORK=host

# 普通镜像 + bridge 网络 ✓
IMAGE_NAME=haroldli/xiaoya-tvbox
NETWORK=bridge
```

### 错误配置（会被拦截）
```bash
# host 镜像 + bridge 网络 ✗
IMAGE_NAME=haroldli/xiaoya-tvbox:host
NETWORK=bridge

# native-host 镜像 + bridge 网络 ✗
IMAGE_NAME=haroldli/xiaoya-tvbox:native-host
NETWORK=bridge

# 普通镜像 + host 网络 ✗
IMAGE_NAME=haroldli/xiaoya-tvbox
NETWORK=host
```

## 自动修正行为

脚本会自动处理以下场景：

1. **选择 :host 镜像时**
   - 自动切换网络模式为 host
   - 提示用户已自动调整

2. **NAS 环境初次运行**
   - 检测到 NAS 环境时
   - 自动使用版本 7 (`xiaoya-tvbox:host`)
   - 自动配置 host 网络

3. **配置冲突时**
   - 拦截不兼容的操作
   - 显示清晰的错误提示
   - 自动回滚到安全状态

## 测试建议

```bash
# 测试 host 镜像强制使用 host 网络
./alist-tvbox.sh
# 选择版本 7 → 应自动切换到 host 网络

# 测试 host 网络强制使用 host 镜像
# 1. 选择版本 4（普通镜像）
# 2. 配置管理 → 网络模式 → host
# 应提示错误并回退

# 测试安装时的兼容性验证
# 手动修改 ~/.config/alist-tvbox/app.conf
IMAGE_NAME=haroldli/xiaoya-tvbox
NETWORK=host
# 执行安装应被拦截
./alist-tvbox.sh install
```

## 修改文件

- `scripts/alist-tvbox.sh` - 主脚本（新增验证逻辑）

## 相关 Issue

解决 80 端口占用问题，确保只有 `:host` 镜像才能使用 host 网络模式。
