# 数据迁移修复 - 使用指南

## 问题回顾

你的NAS上 `alist-tvbox.sh` 脚本将挂载路径从 `/etc/xiaoya/` 改成了 `/volume2/docker/alist-tvbox/`，但没有自动复制文件，导致：
1. 容器在新路径初始化了全新数据库
2. 生成了新的管理员账号
3. 旧路径的数据没有被迁移

## 修复内容

已修复 `scripts/alist-tvbox.sh` 的迁移逻辑：

### 改进1：迁移时机优化
- **之前**：在容器可能已删除后检查迁移
- **之后**：在删除容器**之前**检查，此时可以检测到容器使用的旧路径

### 改进2：智能场景处理
- **场景1**：容器正在使用旧路径 → 自动迁移
- **场景2**：旧路径有数据但容器不存在 → 询问用户是否迁移
- **场景3**：目标目录已有数据 → 警告用户并跳过（避免覆盖）

### 改进3：更好的用户体验
- 详细的进度提示
- 关键操作前询问确认
- 迁移失败时的清晰错误信息

## 在NAS上使用

### 步骤1：更新脚本

将修复后的脚本传到NAS：

```bash
# 在开发机上
scp scripts/alist-tvbox.sh your-nas:/path/to/alist-tvbox/scripts/

# 或者通过git拉取
# 在NAS上
cd /path/to/alist-tvbox
git pull
```

### 步骤2：测试当前状态

```bash
# 在NAS上运行测试脚本
./scripts/test-migration.sh
```

这个脚本会检查：
- 旧路径 `/etc/xiaoya/` 是否存在及大小
- 新路径配置和状态
- 容器挂载情况
- 是否需要迁移

### 步骤3：执行迁移（如果需要）

#### 场景A：还没有运行过新版本容器

如果你还没有在新路径运行容器：

```bash
./scripts/alist-tvbox.sh install
```

脚本会自动检测并迁移数据。

#### 场景B：已经在新路径初始化了容器（你的情况）

如果你已经手动复制了文件：

```bash
# 1. 停止容器
docker stop xiaoya-tvbox

# 2. 创建迁移标记，避免重复提示
touch /volume2/docker/alist-tvbox/.v3

# 3. 重启容器
docker start xiaoya-tvbox

# 4. 验证数据正确后，删除旧路径
rm -rf /etc/xiaoya/
```

如果你想重新从旧路径迁移（覆盖手动复制的内容）：

```bash
# 1. 停止并删除容器
docker rm -f xiaoya-tvbox

# 2. 删除新路径内容（保留旧路径）
rm -rf /volume2/docker/alist-tvbox/*

# 3. 运行安装，脚本会检测到容器之前使用旧路径并自动迁移
./scripts/alist-tvbox.sh install
```

### 步骤4：验证迁移结果

```bash
# 检查新路径文件数量
find /volume2/docker/alist-tvbox/ -type f | wc -l

# 检查旧路径文件数量（应该相同或新路径更多）
find /etc/xiaoya/ -type f | wc -l

# 检查容器挂载点
docker inspect xiaoya-tvbox --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}'

# 应该输出：/volume2/docker/alist-tvbox

# 检查应用是否正常运行
curl -f http://localhost:4567/ && echo "✓ 应用正常"
```

### 步骤5：清理旧路径

确认一切正常后，删除旧路径释放空间：

```bash
# 可选：先备份
tar czf ~/xiaoya-backup-$(date +%Y%m%d).tar.gz /etc/xiaoya/

# 删除旧路径
rm -rf /etc/xiaoya/

# 检查释放的空间
df -h
```

## 如果遇到问题

### 问题1：迁移失败，提示权限错误

```bash
# 以root权限运行
sudo ./scripts/alist-tvbox.sh install

# 或手动复制
sudo cp -a /etc/xiaoya/. /volume2/docker/alist-tvbox/
sudo chown -R $(id -u):$(id -g) /volume2/docker/alist-tvbox/
```

### 问题2：容器启动后数据不对

```bash
# 检查容器实际挂载的路径
docker inspect xiaoya-tvbox --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'

# 检查配置文件
cat ~/.config/alist-tvbox/app.conf | grep BASE_DIR
```

### 问题3：想回退到旧路径

```bash
# 修改配置
sed -i 's|^BASE_DIR=.*|BASE_DIR=/etc/xiaoya|' ~/.config/alist-tvbox/app.conf

# 重建容器
./scripts/alist-tvbox.sh install
```

## 文件清单

修改的文件：
- `scripts/alist-tvbox.sh` - 主脚本，修复了迁移逻辑
- `scripts/MIGRATION_FIX.md` - 详细的修复说明和测试用例
- `scripts/test-migration.sh` - 迁移状态检查工具

## 联系支持

如果遇到无法解决的问题，请提供：
1. `./scripts/test-migration.sh` 的输出
2. 容器日志：`docker logs xiaoya-tvbox`
3. 错误信息截图
