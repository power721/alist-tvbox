# AList-TvBox 脚本修复总结

## 修复清单

本次修复了 `scripts/alist-tvbox.sh` 脚本的两个关键问题：

### 1. ✅ 数据迁移逻辑修复（提交 25ad8011 + 28c82b2d）

**问题**：脚本将挂载路径从 `/etc/xiaoya/` 改成 `/volume2/docker/alist-tvbox/`（或其他新路径）时，数据没有被自动迁移。

**原因**：
- 迁移检查在容器可能已删除后执行，无法检测旧挂载点
- 目标目录有任何文件就跳过迁移，导致容器初始化后无法自动迁移
- 缺少用户交互确认

**修复内容**：
- ✅ 在删除容器**之前**执行迁移检查，此时可以检测到容器使用的旧路径
- ✅ 场景1：容器使用旧路径 → 自动迁移
- ✅ 场景2：旧路径有数据但容器不存在 → 询问用户是否迁移
- ✅ 场景3：目标目录已有数据 → 警告用户而不是静默跳过
- ✅ 改进初始化判断，排除 `.v3` 标记文件的影响
- ✅ 新增 `test-migration.sh` 诊断工具
- ✅ 新增 `MIGRATION_GUIDE.md` 用户指南

**文件**：
- `scripts/alist-tvbox.sh` - `migrate_legacy_data()` 函数（第613-720行）
- `scripts/alist-tvbox.sh` - `install_container()` 函数（第723-760行）
- `scripts/test-migration.sh` - 新增迁移状态检查工具
- `scripts/MIGRATION_GUIDE.md` - 新增用户使用指南
- `scripts/MIGRATION_FIX.md` - 技术细节文档

---

### 2. ✅ 网络模式切换修复（提交 9fadfa74）

**问题**：脚本无法正确切换到 host 网络模式。配置文件显示 host，但容器实际运行在 bridge 模式。

**原因**：
- `show_menu()` 调用 `sync_runtime_config()` 从容器读取网络模式
- 覆盖内存中的 `CONFIG["NETWORK"]`
- 用户修改配置文件后，重建容器时仍使用被覆盖的内存值

**修复内容**：
- ✅ `show_network_menu()` 开始时从配置文件重新加载网络模式
- ✅ `show_config_menu()` 每次显示时重新加载所有关键配置
- ✅ 显示"当前配置"和"容器实际"两个值，清晰展示差异
- ✅ 确保重建容器时使用配置文件中的值

**文件**：
- `scripts/alist-tvbox.sh` - `show_network_menu()` 函数（第1213-1269行）
- `scripts/alist-tvbox.sh` - `show_config_menu()` 函数（第1313-1344行）
- `scripts/NETWORK_MODE_FIX.md` - 技术细节文档

---

## 部署到NAS

### 方法1：通过 Git 拉取（推荐）

```bash
# 在NAS上
cd /path/to/alist-tvbox
git fetch origin
git checkout fix-new-ui
git pull
```

### 方法2：直接复制脚本

```bash
# 在开发机上
scp scripts/alist-tvbox.sh your-nas:/path/to/alist-tvbox/scripts/
scp scripts/test-migration.sh your-nas:/path/to/alist-tvbox/scripts/
scp scripts/MIGRATION_GUIDE.md your-nas:/path/to/alist-tvbox/scripts/

# 在NAS上添加执行权限
chmod +x /path/to/alist-tvbox/scripts/alist-tvbox.sh
chmod +x /path/to/alist-tvbox/scripts/test-migration.sh
```

---

## 在NAS上使用

### 测试迁移状态

```bash
./scripts/test-migration.sh
```

这会检查：
- 旧路径 `/etc/xiaoya/` 是否存在及数据量
- 新路径配置和状态
- 容器挂载情况
- 是否需要迁移

### 解决数据迁移问题

#### 场景A：已手动复制文件

```bash
# 创建标记文件，避免重复提示
touch /volume2/docker/alist-tvbox/.v3

# 验证数据正确后，删除旧路径
rm -rf /etc/xiaoya/
```

#### 场景B：想重新自动迁移

```bash
# 1. 停止并删除容器
docker rm -f xiaoya-tvbox

# 2. 清空新路径（谨慎！会删除现有数据）
rm -rf /volume2/docker/alist-tvbox/*

# 3. 运行安装，自动迁移
./scripts/alist-tvbox.sh install
```

### 解决网络模式问题

```bash
# 1. 运行脚本
./scripts/alist-tvbox.sh

# 2. 选择: 8. 配置管理

# 3. 选择: 6. 网络模式

# 4. 查看显示：
#    当前配置: host
#    容器实际: bridge (需要重建容器使配置生效)

# 5. 如果配置已是 host，直接按 0 返回，然后选择"1. 安装/更新"重建容器
#    如果配置是 bridge，选择"2. host模式"然后确认重建

# 6. 验证
docker inspect xiaoya-tvbox --format '{{.HostConfig.NetworkMode}}'
# 应输出: host
```

---

## 验证修复

### 验证数据迁移

```bash
# 检查新路径文件数量
find /volume2/docker/alist-tvbox/ -type f | wc -l

# 检查旧路径文件数量（应该相同或新路径更多）
find /etc/xiaoya/ -type f 2>/dev/null | wc -l

# 检查容器挂载点
docker inspect xiaoya-tvbox --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}'
# 应输出: /volume2/docker/alist-tvbox
```

### 验证网络模式

```bash
# 检查配置文件
cat ~/.config/alist-tvbox/app.conf | grep NETWORK
# 应输出: NETWORK=host

# 检查容器实际网络模式
docker inspect xiaoya-tvbox --format '{{.HostConfig.NetworkMode}}'
# 应输出: host

# 验证 host 模式服务可访问
curl -f http://localhost:4567/ && echo "✓ 管理应用正常"
curl -f http://localhost:5234/ && echo "✓ AList正常"
```

---

## 提交历史

```
9fadfa74 fix(scripts): 修复网络模式切换失败问题
28c82b2d docs(scripts): 添加迁移测试工具和使用指南
25ad8011 fix(scripts): 修复数据迁移逻辑，确保从旧路径正确迁移数据
```

---

## 向后兼容性

✅ 完全向后兼容：
- 已迁移的用户不受影响（检测到 `.v3` 标记会跳过）
- 不会覆盖手动复制的数据
- 配置正确的用户不受影响
- 保留旧路径文件，需用户手动删除（更安全）

---

## 文档

详细的技术文档和使用指南：
- `scripts/MIGRATION_FIX.md` - 数据迁移修复的技术细节和测试用例
- `scripts/MIGRATION_GUIDE.md` - 数据迁移用户使用指南（简明易懂）
- `scripts/NETWORK_MODE_FIX.md` - 网络模式修复的技术细节和测试用例
- `scripts/test-migration.sh` - 自动化迁移状态检查工具

---

## 支持

如果在NAS上遇到问题，请提供：
1. `./scripts/test-migration.sh` 的输出
2. `cat ~/.config/alist-tvbox/app.conf` 的内容
3. `docker inspect xiaoya-tvbox` 的输出（特别是 Mounts 和 NetworkMode 部分）
4. 容器日志：`docker logs xiaoya-tvbox | tail -100`
