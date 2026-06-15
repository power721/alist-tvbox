# Scripts Directory

此目录包含**用户手动安装和更新**的脚本，用于在宿主机上部署和管理 AList-TVBox。

⚠️ **重要区别**：
- **此目录 (`scripts/`)**: 用户手动执行的安装/更新脚本
- **`docker/scripts/`**: Docker 容器内部使用的初始化脚本

## 目录结构

```
scripts/
├── install.sh              # 全新安装脚本
├── install-service.sh      # 安装为系统服务
├── update_new.sh           # 更新标准版
├── update_xiaoya.sh        # 更新 xiaoya 版
├── update_hostmode.sh      # 更新 hostmode 版
├── update_native.sh        # 更新 native 版
├── update_native_host.sh   # 更新 native host 版
├── alist-tvbox.sh          # 服务控制脚本
├── native.sh               # Native 版本启动脚本
└── tests/                  # 测试脚本
```

## 使用场景

### 1. 首次安装
```bash
curl -fsSL https://d.har01d.cn/install.sh | bash
```

### 2. 更新到最新版本
```bash
# 标准版
bash <(curl -fsSL https://d.har01d.cn/update_new.sh)

# Xiaoya 版
bash <(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)

# Hostmode 版
bash <(curl -fsSL https://d.har01d.cn/update_hostmode.sh)
```

### 3. 安装为系统服务
```bash
sudo ./scripts/install-service.sh
```

## 与 Docker 脚本的关系

| 位置 | 用途 | 执行环境 | 修改频率 |
|------|------|---------|---------|
| `scripts/` | 宿主机安装/更新 | 用户终端 | 低 |
| `docker/scripts/` | 容器初始化 | 容器内部 | 中 |

**不要混淆这两个目录！**

- `scripts/entrypoint.sh` - ❌ 已废弃，被移到 `docker/scripts/`
- `scripts/init.sh` - ❌ 已废弃，被移到 `docker/scripts/`
- `scripts/update_*.sh` - ✅ 用户更新脚本，保留

## 维护指南

### 添加新的更新脚本
1. 复制现有的 `update_*.sh` 作为模板
2. 修改镜像名称和标签
3. 更新文档

### 修改容器初始化逻辑
**不要修改此目录！** 前往 `docker/scripts/` 进行修改。

## 已清理的文件

以下文件已移至 `docker/scripts/`：
- ~~`entrypoint.sh`~~ → `docker/scripts/entrypoint.sh`
- ~~`entrypoint-native.sh`~~ → `docker/scripts/entrypoint-native.sh`
- ~~`init.sh`~~ → `docker/scripts/init-xiaoya.sh` + `init-alist.sh`
