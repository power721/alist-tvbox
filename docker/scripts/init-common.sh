#!/bin/sh
# 共同初始化逻辑 - 所有模式通用

# 加载依赖
. /docker/scripts/lib/common.sh
. /docker/scripts/lib/download.sh
. /docker/scripts/lib/version.sh

# 创建必要的目录
init_directories() {
  log_info "Creating required directories"
  ensure_dir /data/atv /data/index /data/backup /data/log /www
}

# 设置符号链接
setup_symlinks() {
  log_info "Setting up symlinks"

  # 清理旧的 index 目录
  [ -d /index ] && rm -rf /index

  safe_symlink /data/index /index
  safe_symlink /data/config /opt/atv/config
  safe_symlink /data/log /opt/atv/log
}

# 解压 cat/pg/zx 资源包
extract_resource_zips() {
  log_info "Extracting resource packages"

  # cat.zip
  if [ ! -d /www/cat ]; then
    log_info "Extracting cat.zip"
    mkdir -p /www/cat
    unzip -q -o /cat.zip -d /www/cat
  fi
  [ -d /data/cat ] && cp -r /data/cat/* /www/cat/

  # pg.zip
  [ ! -f /data/pg.zip ] && cp /pg.zip /data/pg.zip
  if [ ! -d /www/pg ]; then
    log_info "Extracting pg.zip"
    mkdir -p /www/pg
    unzip -q -o /data/pg.zip -d /www/pg
  fi
  [ -d /data/pg ] && cp -r /data/pg/* /www/pg/

  # zx.zip
  [ ! -f /data/zx.zip ] && cp /zx.zip /data/zx.zip
  if [ ! -d /www/zx ]; then
    log_info "Extracting zx.zip"
    mkdir -p /www/zx
    unzip -q -o /data/zx.zip -d /www/zx
  fi
}

# 解压内置 115 索引，仅在用户数据目录不存在时初始化
seed_index115() {
  if [ -d /data/index115 ]; then
    log_info "115 index already exists, skipping seed"
    return 0
  fi

  if [ ! -f /115.index.zip ]; then
    log_warn "115.index.zip not found, skipping seed"
    return 0
  fi

  log_info "Seeding 115 index"
  rm -rf /data/index115.tmp
  mkdir -p /data/index115.tmp
  unzip -q /115.index.zip -d /data/index115.tmp
  mv /data/index115.tmp /data/index115
}

# 下载并解压 tvbox.zip
download_tvbox() {
  log_info "Downloading tvbox.zip"
  cd /www || return 1

  if ! download_and_extract_zip \
    "https://raw.githubusercontent.com/xiaoyaliu00/data/main/tvbox.zip" \
    "tvbox.zip" \
    "." \
    "/tvbox.zip"; then
    log_error "Failed to get tvbox.zip"
    return 1
  fi

  # 处理自定义配置
  if [ -f /data/my.json ]; then
    rm -f /www/tvbox/my.json
    ln -s /data/my.json /www/tvbox/my.json
  fi

  if [ -f /data/iptv.m3u ]; then
    ln -s /data/iptv.m3u /www/tvbox/iptv.m3u
  fi

  rm -f tvbox.zip
  return 0
}
