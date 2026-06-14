#!/usr/bin/env bash
# 测试迁移逻辑的脚本
# 用法：./scripts/test-migration.sh

set -euo pipefail

echo "=== 数据迁移逻辑测试脚本 ==="
echo ""

# 检查当前环境
echo "1. 检查旧路径 /etc/xiaoya/"
if [[ -d "/etc/xiaoya" ]]; then
  echo "   ✓ 旧路径存在"
  echo "   文件数量: $(find /etc/xiaoya/ -type f 2>/dev/null | wc -l)"
  echo "   目录大小: $(du -sh /etc/xiaoya/ 2>/dev/null | cut -f1)"
else
  echo "   ✗ 旧路径不存在"
fi

echo ""
echo "2. 检查配置文件"
CONFIG_FILE="$HOME/.config/alist-tvbox/app.conf"
if [[ -f "$CONFIG_FILE" ]]; then
  echo "   ✓ 配置文件存在: $CONFIG_FILE"
  BASE_DIR=$(grep "^BASE_DIR=" "$CONFIG_FILE" | cut -d'=' -f2)
  echo "   配置的数据目录: $BASE_DIR"
else
  echo "   ✗ 配置文件不存在"
fi

echo ""
echo "3. 检查新路径 ${BASE_DIR:-未配置}"
if [[ -n "${BASE_DIR:-}" && -d "$BASE_DIR" ]]; then
  echo "   ✓ 新路径存在"
  echo "   文件数量: $(find "$BASE_DIR" -type f 2>/dev/null | wc -l)"
  echo "   目录大小: $(du -sh "$BASE_DIR" 2>/dev/null | cut -f1)"
  if [[ -f "$BASE_DIR/.v3" ]]; then
    echo "   ✓ 存在 .v3 标记文件（已迁移）"
  else
    echo "   ✗ 不存在 .v3 标记文件（未迁移）"
  fi
else
  echo "   ✗ 新路径不存在或未配置"
fi

echo ""
echo "4. 检查容器状态"
for container in "xiaoya-tvbox" "alist-tvbox"; do
  if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${container}\$"; then
    echo "   ✓ 容器 $container 存在"
    mount_point=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "$container" 2>/dev/null)
    if [[ -n "$mount_point" ]]; then
      echo "     挂载点: $mount_point"
    fi
    status=$(docker inspect --format '{{.State.Status}}' "$container" 2>/dev/null)
    echo "     状态: $status"
  fi
done

echo ""
echo "5. 迁移建议"
if [[ -d "/etc/xiaoya" && -n "$(ls -A /etc/xiaoya 2>/dev/null)" ]]; then
  if [[ -n "${BASE_DIR:-}" && "$BASE_DIR" != "/etc/xiaoya" ]]; then
    if [[ -f "$BASE_DIR/.v3" ]]; then
      echo "   ✓ 数据已迁移"
      echo "   建议：确认新路径数据正常后，可删除旧路径释放空间："
      echo "   rm -rf /etc/xiaoya/"
    else
      echo "   ⚠ 需要迁移"
      echo "   建议：运行安装命令自动迁移："
      echo "   ./scripts/alist-tvbox.sh install"
    fi
  fi
else
  echo "   ✓ 无需迁移"
fi

echo ""
echo "=== 测试完成 ==="
