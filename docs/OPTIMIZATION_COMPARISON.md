# 优化后的文件对比

## 原始结构 vs 新结构

### 原始（混乱）
```
.
├── entrypoint.sh          # 30行，JVM入口
├── entrypoint-native.sh   # 30行，Native入口
├── init.sh                # 259行，巨大的初始化脚本
├── docker/
│   ├── Dockerfile
│   ├── Dockerfile-xiaoya
│   ├── Dockerfile-host
│   ├── Dockerfile-native
│   ├── Dockerfile-tg      # 已注释，待删除
│   └── Dockerfile-xiaoya-tg  # 已注释，待删除
└── scripts/
    ├── entrypoint.sh      # 25行，与根目录重复但不同
    ├── init.sh            # 134行，与根目录重复但不同
    └── update_*.sh        # 用户更新脚本
```

### 优化后（清晰）
```
.
├── docker/
│   ├── Dockerfile         # ✅ 使用新脚本路径
│   ├── Dockerfile-xiaoya  # ✅ 使用新脚本路径
│   ├── Dockerfile-host    # ✅ 使用新脚本路径
│   ├── Dockerfile-native  # ✅ 使用新脚本路径
│   └── scripts/           # ✨ 新增：容器脚本目录
│       ├── README.md      # 📝 说明文档
│       ├── entrypoint.sh  # 统一 JVM 入口
│       ├── entrypoint-native.sh  # 统一 Native 入口
│       ├── init-common.sh # 通用初始化
│       ├── init-alist.sh  # 标准模式初始化
│       ├── init-xiaoya.sh # Xiaoya 模式初始化
│       └── lib/           # 公共函数库
│           ├── common.sh  # 日志、错误处理
│           ├── database.sh  # 数据库操作
│           ├── download.sh  # 下载工具
│           ├── proxy.sh   # 代理配置
│           └── version.sh # 版本管理
├── scripts/
│   ├── README.md          # 📝 说明与 docker/scripts/ 的区别
│   └── update_*.sh        # 用户更新脚本（保持不变）
└── docs/
    └── DOCKER_SCRIPTS_REFACTOR.md  # 📝 重构详细文档
```

## 关键改进点

### 1. 职责分离
| 文件 | 行数 | 职责 |
|------|------|------|
| init-common.sh | ~80 | 通用逻辑（目录、链接、解压） |
| init-alist.sh | ~50 | 标准模式（精简） |
| init-xiaoya.sh | ~180 | Xiaoya 模式（完整） |
| lib/database.sh | ~70 | 数据库操作 |
| lib/download.sh | ~50 | 下载工具 |

**对比原来的 init.sh (259行)**：
- ✅ 逻辑清晰，易于理解
- ✅ 修改范围小，降低风险
- ✅ 可以独立测试

### 2. 代码复用
原来的下载逻辑：
```bash
# init.sh 第 4-27 行：download_with_proxy() 函数
# init.sh 第 39-62 行：init() 内重复实现
```

优化后：
```bash
# lib/download.sh：统一实现
# 所有需要下载的地方调用 download_with_proxy()
```

### 3. 功能隔离

**原来**：所有镜像都执行完整的 init.sh
```bash
# alist-tvbox:latest 也会执行：
- 下载 xiaoya 远程索引
- 更新 sqlite 数据库
- 同步分享索引
❌ 不需要但浪费时间和网络
```

**优化后**：根据 INSTALL 选择
```bash
case "$INSTALL" in
  new|docker)
    init-alist.sh  # 只做基础初始化
    ;;
  xiaoya|hostmode)
    init-xiaoya.sh # 完整功能
    ;;
esac
✅ 各取所需，干净高效
```

## 验证清单

构建前检查：
- [x] 脚本语法检查通过（sh -n）
- [x] 文件权限正确（chmod +x）
- [x] Git 提交完成
- [x] Dockerfile 路径更新

构建时检查：
- [ ] docker build 成功（6 个变体）
- [ ] 镜像大小合理
- [ ] 无错误日志

运行时检查：
- [ ] 容器启动成功
- [ ] 初始化日志清晰
- [ ] 功能正常（标准版 vs xiaoya 版）
- [ ] 文件权限正确

## 回滚方案

如果发现问题：
```bash
# 1. 切回 master
git checkout master

# 2. 如果已合并，可以 revert
git revert <commit-hash>

# 3. 如果已发布镜像，回退到上一个标签
docker pull haroldli/alist-tvbox:<previous-tag>
```

## 合并建议

1. 先在测试环境构建和运行
2. 验证标准版和 xiaoya 版都正常
3. 确认 CI/CD 构建通过
4. 再合并到 master
