# Docker Scripts Optimization

本次重构优化了 Docker 容器的初始化脚本，解决了代码混乱和重复的问题。

## 主要改进

### 1. 脚本模块化
- 将 259 行的 `init.sh` 拆分为多个职责清晰的模块
- 创建 `lib/` 目录存放公共函数库
- 分离标准模式和 xiaoya 模式的初始化逻辑

### 2. 清理冗余
- 删除已注释的 TG Dockerfile（Dockerfile-tg, Dockerfile-xiaoya-tg）
- 移除根目录的重复脚本（entrypoint.sh, init.sh, entrypoint-native.sh）
- 统一脚本路径到 `docker/scripts/`

### 3. 目录结构

```
docker/scripts/
├── lib/                    # 公共函数库
│   ├── common.sh          # 日志、错误处理
│   ├── database.sh        # H2 数据库操作
│   ├── download.sh        # 下载工具（代理 fallback）
│   ├── proxy.sh           # 代理配置
│   └── version.sh         # 版本管理
├── entrypoint.sh          # JVM 版本入口
├── entrypoint-native.sh   # Native 版本入口
├── init-common.sh         # 通用初始化
├── init-alist.sh          # 标准模式初始化
└── init-xiaoya.sh         # Xiaoya 模式初始化
```

### 4. 模式分离

**标准模式 (INSTALL=new/docker)**:
- 使用 `init-alist.sh`
- 精简版本，无 xiaoya 特定功能
- 适用镜像: `alist-tvbox:latest`, `alist-tvbox:native`

**Xiaoya 模式 (INSTALL=xiaoya/hostmode)**:
- 使用 `init-xiaoya.sh`
- 完整功能，包含远程索引更新、数据库同步
- 适用镜像: `xiaoya-tvbox:latest`, `xiaoya-tvbox:hostmode`, native 版本

## 修改的文件

### 新增
- `docker/scripts/lib/*.sh` (5 个库文件)
- `docker/scripts/init-*.sh` (3 个初始化脚本)
- `docker/scripts/entrypoint*.sh` (2 个入口脚本)
- `docker/scripts/README.md`
- `scripts/README.md`

### 修改
- `docker/Dockerfile` - 使用新脚本路径
- `docker/Dockerfile-xiaoya` - 使用新脚本路径
- `docker/Dockerfile-host` - 使用新脚本路径
- `docker/Dockerfile-native` - 使用新脚本路径
- `docker/Dockerfile-alist-native` - 使用新脚本路径
- `docker/Dockerfile-native-host` - 使用新脚本路径

### 删除
- `entrypoint.sh` (根目录)
- `init.sh` (根目录)
- `entrypoint-native.sh` (根目录)
- `docker/Dockerfile-tg`
- `docker/Dockerfile-xiaoya-tg`

## 优势

### 代码质量
- ✅ 职责单一：每个脚本功能明确
- ✅ 可维护性：模块化便于定位和修改
- ✅ 可测试性：函数库可单独测试

### 功能隔离
- ✅ 标准版不再包含 xiaoya 逻辑
- ✅ 避免污染：精简版镜像不执行不需要的代码
- ✅ 清晰边界：通过 `$INSTALL` 变量选择初始化路径

### 维护效率
- ✅ 减少重复：下载逻辑统一在 `lib/download.sh`
- ✅ 易于扩展：添加新功能时知道该修改哪个文件
- ✅ 文档完善：README 说明用途和调用关系

## 向后兼容性

所有 Dockerfile 已更新，CI/CD 构建流程无需修改：
- 环境变量 `INSTALL` 保持不变
- 容器行为完全一致
- 日志输出格式改进但不影响功能

## 未来优化建议

### Phase 2（可选）
- 统一 Dockerfile 使用 build-args
- 合并 CI/CD workflows 减少重复

### Phase 3（可选）
- 版本管理改用 JSON 格式
- 添加健康检查和优雅停机

### Phase 4（可选）
- 幂等性设计（重复执行安全）
- 单元测试覆盖
