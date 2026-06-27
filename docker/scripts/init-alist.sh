#!/bin/sh
# AList-TVBox 精简模式初始化（不包含 xiaoya 特定功能）

set -e

# 加载依赖
. /docker/scripts/lib/common.sh
. /docker/scripts/lib/database.sh
. /docker/scripts/lib/version.sh
. /docker/scripts/init-common.sh

log_info "=========================================="
log_info "AList-TVBox Initialization (Standard Mode)"
log_info "=========================================="
log_info "Install mode: $INSTALL"
cat /app_version
date
uname -mor

# 数据库升级和恢复
upgrade_h2
restore_database

# 创建运行期目录和符号链接。/opt/alist/data 可能是持久化挂载，
# 但 /opt/atv/log 等路径属于新容器文件系统，每次启动都需要恢复。
init_directories
setup_symlinks

# 检查是否已经初始化
if is_initialized; then
  log_info "Already initialized, skipping first-time setup"
else
  log_info "Running first-time initialization"

  # 配置 AList
  if [ ! -f /opt/alist/data/config.json ]; then
    log_info "Initializing AList config"
    cp /alist.json /opt/alist/data/config.json
    sed -i 's/127.0.0.1/0.0.0.0/' /opt/alist/data/config.json
  fi

  # 显示 AList 管理员密码（仅在独立 AList 二进制存在时）
  if [ -f /opt/alist/alist ]; then
    cd /opt/alist
    /opt/alist/alist admin || true
  else
    log_info "Embedded AList mode (native), admin password managed by application"
  fi

  # 下载 tvbox 资源
  download_tvbox

  # 标记为已初始化
  mark_initialized
fi

# 解压资源包（每次启动都检查）
extract_resource_zips

# 初始化 115 索引（仅当 /data/index115 不存在）
seed_index115

log_info "AList-TVBox initialization completed successfully"
