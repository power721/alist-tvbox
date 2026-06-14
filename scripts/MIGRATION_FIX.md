# 数据迁移逻辑修复说明

## 问题描述

原脚本在从 `/etc/xiaoya/` 迁移到 `/volume2/docker/alist-tvbox/`（或其他新路径）时存在以下问题：

1. **时机错误**：迁移检查在 `install_container()` 中执行，但此时容器可能已被删除
2. **检查过于严格**：如果目标目录已有任何文件（即使是初始化产生的），就跳过迁移
3. **缺少用户确认**：当检测到旧数据但无法确定是否应迁移时，没有询问用户

### 导致的后果

- 容器在新路径初始化了全新的数据库和管理员账号
- 旧路径 `/etc/xiaoya/` 的数据没有被迁移
- 用户需要手动复制文件

## 修复内容

### 1. `migrate_legacy_data()` 函数改进

**场景1：容器正在使用旧路径**
- 在容器删除**之前**检测容器挂载点
- 如果容器绑定了 `/etc/xiaoya`，自动迁移到新路径
- 如果目标目录已有数据，**警告用户**而不是静默跳过

**场景2：容器不存在或使用其他路径，但旧路径有数据**
- 检测到 `/etc/xiaoya/` 有数据但容器未使用时
- **询问用户**是否迁移（而不是静默跳过）

**场景3：旧路径不存在或为空**
- 正常标记完成，无需迁移

### 2. `install_container()` 函数改进

**调整迁移时机**
```bash
# 之前：在函数开始时调用，此时容器可能已不存在
migrate_legacy_data

# 之后：在删除容器之前调用，可以检测到容器的挂载点
if ! migrate_legacy_data; then
  echo -e "${RED}数据迁移失败，安装中止${NC}"
  return 1
fi
remove_opposite_container
```

**改进初始化判断**
```bash
# 检查基础目录是否为空（排除 .v3 标记文件）
local file_count=$(find "${CONFIG[BASE_DIR]}" -mindepth 1 ! -name '.v3' 2>/dev/null | wc -l)
if [[ "$file_count" -eq 0 ]]; then
  INIT=true
fi
```

这样即使目录存在 `.v3` 标记文件，也能正确判断是否需要显示初始化信息。

## 测试场景

### 测试1：容器使用旧路径
```bash
# 前置条件
# - 容器 xiaoya-tvbox 挂载 /etc/xiaoya 到 /data
# - /etc/xiaoya/ 有数据
# - 配置改为新路径 /volume2/docker/alist-tvbox/

./scripts/alist-tvbox.sh install

# 预期结果
# - 检测到容器使用旧路径
# - 自动迁移 /etc/xiaoya/ -> /volume2/docker/alist-tvbox/
# - 创建 .v3 标记文件
# - 容器使用新路径启动
```

### 测试2：旧路径有数据，但容器不存在
```bash
# 前置条件
# - 无容器运行
# - /etc/xiaoya/ 有数据
# - 配置为新路径 /volume2/docker/alist-tvbox/

./scripts/alist-tvbox.sh install

# 预期结果
# - 询问用户是否迁移
# - 用户确认后迁移
# - 容器使用新路径启动
```

### 测试3：目标目录已有数据
```bash
# 前置条件
# - 容器使用旧路径
# - /volume2/docker/alist-tvbox/ 已有数据（用户手动复制的）

./scripts/alist-tvbox.sh install

# 预期结果
# - 警告目标目录已有数据
# - 跳过迁移，使用目标目录现有数据
# - 容器正常启动
```

### 测试4：已迁移过（存在 .v3 标记）
```bash
# 前置条件
# - /volume2/docker/alist-tvbox/.v3 存在

./scripts/alist-tvbox.sh install

# 预期结果
# - 检测到 .v3 标记，跳过迁移
# - 容器正常启动
```

## 向后兼容性

✅ 完全向后兼容：
- 如果已经迁移过（有 `.v3` 标记），不会重复执行
- 如果用户手动复制了文件，会检测到并跳过
- 旧路径文件被保留，需要用户手动删除（更安全）

## 使用建议

### 对于已经手动复制文件的用户

如果你已经手动复制了文件到新路径：

```bash
# 1. 创建标记文件，避免再次提示迁移
touch /volume2/docker/alist-tvbox/.v3

# 2. 确认容器运行正常后，删除旧路径释放空间
rm -rf /etc/xiaoya/

# 或者先备份
tar czf ~/xiaoya-backup-$(date +%Y%m%d).tar.gz /etc/xiaoya/
rm -rf /etc/xiaoya/
```

### 对于未迁移的用户

直接运行脚本即可自动迁移：

```bash
./scripts/alist-tvbox.sh install
```

## 变更文件

- `scripts/alist-tvbox.sh`
  - `migrate_legacy_data()` 函数：第613-720行
  - `install_container()` 函数：第723-760行

## 提交信息建议

```
fix(scripts): 修复数据迁移逻辑，确保从旧路径正确迁移数据

问题：
- 迁移检查在容器删除后执行，无法检测旧挂载点
- 目标目录有任何文件就跳过迁移，导致初始化后无法迁移
- 缺少用户交互确认

修复：
- 在删除容器之前执行迁移检查
- 目标目录非空时警告用户而不是静默跳过
- 增加场景2：询问用户是否迁移旧数据
- 改进初始化判断逻辑，排除 .v3 标记文件

影响：
- 解决了从 /etc/xiaoya/ 迁移到新路径时数据丢失问题
- 保持向后兼容，已迁移用户不受影响
```
