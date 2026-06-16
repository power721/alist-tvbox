# Docker Scripts Directory

此目录包含所有 Docker 容器的初始化和入口脚本。

## 目录结构

```
docker/scripts/
├── lib/                  # 公共函数库
│   ├── common.sh        # 日志、错误处理
│   ├── database.sh      # H2 数据库升级和恢复
│   ├── download.sh      # 下载工具（支持代理 fallback）
│   ├── proxy.sh         # 代理配置
│   └── version.sh       # 版本管理工具
├── entrypoint.sh        # JVM 版本入口脚本
├── entrypoint-native.sh # Native 版本入口脚本
├── init-common.sh       # 通用初始化逻辑
├── init-alist.sh        # AList-TVBox 标准模式初始化
└── init-xiaoya.sh       # Xiaoya 模式初始化
```

## 模式说明

### 标准模式 (INSTALL=new/docker)
- 使用 `init-alist.sh`
- 精简版本，不包含 xiaoya 特定功能
- 镜像：`alist-tvbox:latest`, `alist-tvbox:native`

### Xiaoya 模式 (INSTALL=xiaoya/hostmode)
- 使用 `init-xiaoya.sh`
- 完整版本，包含：
  - 远程索引更新
  - 数据库自动同步
  - 分享索引管理
- 镜像：`xiaoya-tvbox:latest`, `xiaoya-tvbox:hostmode`

## 脚本调用关系

```
entrypoint.sh
  ├── lib/common.sh
  ├── lib/proxy.sh
  └── 根据 $INSTALL 选择:
      ├── init-alist.sh
      │   ├── init-common.sh
      │   ├── lib/database.sh
      │   └── lib/version.sh
      └── init-xiaoya.sh
          ├── init-common.sh
          ├── lib/database.sh
          ├── lib/download.sh
          └── lib/version.sh
```

## 环境变量

- `INSTALL`: 安装模式 (new/docker/xiaoya/hostmode/native/native-host)
- `MEM_OPT`: JVM 内存参数
- `ALIST_PORT`: AList 端口
- `NATIVE`: 是否为 Native 版本

## 维护说明

1. **添加新功能**: 
   - 通用功能 → `lib/` 或 `init-common.sh`
   - 模式特定 → `init-alist.sh` 或 `init-xiaoya.sh`

2. **修改初始化逻辑**:
   - 先确定是哪个模式需要修改
   - 不要在 `init-common.sh` 添加模式特定代码

3. **调试**:
   - 日志输出到 `/data/log/init.log`
   - 使用 `log_info/log_warn/log_error` 记录关键步骤
