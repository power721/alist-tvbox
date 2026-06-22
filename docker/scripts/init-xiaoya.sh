#!/bin/sh
# Xiaoya 模式初始化（包含完整的 xiaoya 特定功能）

set -e

# 加载依赖
. /docker/scripts/lib/common.sh
. /docker/scripts/lib/database.sh
. /docker/scripts/lib/download.sh
. /docker/scripts/lib/version.sh
. /docker/scripts/init-common.sh

log_info "=========================================="
log_info "AList-TVBox Initialization (Xiaoya Mode)"
log_info "=========================================="
log_info "Install mode: $INSTALL"
cat /app_version
version=$(head -n1 /docker.version)
log_info "xiaoya version: $version"
date
uname -mor

# 更新电影数据
update_movie() {
  local LOCAL="0.0.0"
  [ -f /data/atv/base_version ] && LOCAL=$(head -n1 /data/atv/base_version)

  local REMOTE=$(head -n1 /base_version)
  log_info "Movie base version: local=$LOCAL, remote=$REMOTE"

  if [ "$LOCAL" != "$REMOTE" ]; then
    log_info "Upgrading movie data"
    unzip -q -o /data.zip -d /data/atv/
    cp /base_version /data/atv/base_version
    rm -f /data/atv/sql/*.sql
  fi
}

# Xiaoya 首次初始化
xiaoya_first_init() {
  log_info "Running xiaoya first-time initialization"

  # 创建目录并解压基础数据
  ensure_dir /var/lib/pxg /www/cgi-bin

  cd /var/lib/pxg
  unzip -q /var/lib/data.zip

  # 移动 AList 数据库
  mv data.db /opt/alist/data/data.db

  # 配置 CGI 脚本
  sed -i 's!/"$after"!"$after"!' search
  mv search /www/cgi-bin/search
  mv sou /www/cgi-bin/sou
  mv whatsnew /www/cgi-bin/whatsnew
  mv header.html /www/cgi-bin/header.html

  # 配置 AList
  if [ ! -f /opt/alist/data/config.json ]; then
    cp /alist.json /opt/alist/data/config.json
    NEW_SECRET=$(tr -dc 'a-zA-Z0-9' < /dev/urandom | head -c 16)
    sed -i "s/Y3JIG4vxT22wn9cq/${NEW_SECRET}/" /opt/alist/data/config.json
    sed -i "s/127.0.0.1/0.0.0.0/" /opt/alist/data/config.json
  fi

  # 配置 nginx
  sed '/location \/dav/i\    location ~* alist {\n        deny all;\n    }\n' nginx.conf > /etc/nginx/http.d/default.conf

  # 解压移动端资源
  mv mobi.tgz /www/mobi.tgz
  cd /www
  tar zxf mobi.tgz
  rm mobi.tgz

  # 更新 AList 数据库
  sqlite3 /opt/alist/data/data.db ".read /update.sql"

  # 下载 tvbox
  download_tvbox

  # 更新电影数据
  update_movie
}

# 更新 xiaoya 数据库和索引
update_xiaoya_data() {
  log_info "Updating xiaoya database and index"
  cd /tmp

  # 下载版本文件和更新包
  download_with_proxy \
    "https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt" \
    "version.txt" || \
    wget -t 3 https://d.har01d.cn/version.txt -O version.txt || true

  download_with_proxy \
    "https://raw.githubusercontent.com/xiaoyaliu00/data/main/update.zip" \
    "update.zip" || \
    wget -t 3 https://d.har01d.cn/update.zip -O update.zip || true

  # 更新数据库
  if [ -f update.zip ]; then
    log_info "Updating AList database"
    unzip -o -q -P abcd update.zip

    entries=$(grep -c 'INSERT INTO x_storages' update.sql 2>/dev/null || echo 0)
    log_info "Database has $entries storage records"

    # 清理 WAL 文件（先执行 checkpoint 再删除）
    sqlite3 /opt/alist/data/data.db "PRAGMA wal_checkpoint(TRUNCATE);" 2>/dev/null || true
    rm -f /opt/alist/data/data.db-shm /opt/alist/data/data.db-wal

    # 修正SQL
    sed -i 's/v3.9.2/v3.44.0/' update.sql
    sed -i 's/pass_code/share_pwd/' update.sql

    # 执行更新
    sqlite3 /opt/alist/data/data.db <<EOF
drop table x_storages;
drop table x_meta;
drop table x_setting_items;
.read update.sql
EOF

    # 更新 opentoken URL
    if [ -f opentoken_url.txt ]; then
      opentoken_url=$(cat opentoken_url.txt)
      sed -i "s#https://api.nn.ci/alist/ali_open/token#$opentoken_url#" /opt/alist/data/config.json
    fi

    rm -f update.zip update.sql opentoken_url.txt
    log_info "Database updated successfully"
  else
    log_warn "Failed to download update.zip, skipping database update"
  fi

  # 更新索引文件
  if [ -f version.txt ]; then
    local remote=$(head -n1 version.txt)
    [ ! -f /data/index/version.txt ] && echo "0.0.0" > /data/index/version.txt
    local local_ver=$(head -n1 /data/index/version.txt)

    log_info "Index version: local=$local_ver, remote=$remote"

    compare_version "$local_ver" "$remote"
    case $? in
      0)
        log_info "Index is up to date"
        ;;
      1)
        log_info "Downloading new index"
        download_with_proxy \
          "https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip" \
          "index.zip" || \
          wget -t 3 https://d.har01d.cn/index.zip -O index.zip || true

        if [ -f index.zip ]; then
          unzip -o -q -P abcd index.zip
          cat index.video.txt index.book.txt index.music.txt index.non.video.txt > /data/index/index.txt
          mv index*.txt /data/index/
          echo "$remote" > /data/index/version.txt
          log_info "Index updated to $remote"
        else
          log_warn "Failed to download index.zip"
        fi
        ;;
      2)
        log_info "Local version is newer, no downgrade needed"
        ;;
    esac
  else
    log_warn "Failed to download version.txt, skipping index update"
  fi

  # 更新分享索引
  local LOCAL="0.0.0"
  [ -f /data/index/share_version ] && LOCAL=$(head -n1 /data/index/share_version)

  unzip -q -o /index.share.zip -d /tmp
  local REMOTE=$(head -n1 /tmp/share_version)

  log_info "Share index version: local=$LOCAL, remote=$REMOTE"

  if [ "$LOCAL" != "$REMOTE" ]; then
    log_info "Upgrading share index"
    mv /tmp/index.share.txt /data/index/index.share.txt
    mv /tmp/share_version /data/index/share_version

    # 清理旧的分享数据
    grep -v "/🈴我的阿里分享/" /data/index/index.video.txt > /data/index/index.video.txt.tmp || true
    grep -v "/🈴我的阿里分享/" /data/index/index.txt > /data/index/index.txt.tmp || true
    mv /data/index/index.video.txt.tmp /data/index/index.video.txt
    mv /data/index/index.txt.tmp /data/index/index.txt

    # 添加新的分享数据
    cat /data/index/index.share.txt >> /data/index/index.video.txt
    cat /data/index/index.share.txt >> /data/index/index.txt
  fi

  rm -f /tmp/index.share.txt index.zip version.txt

  # 更新版本信息到数据库
  app_ver=$(head -n1 /app_version)
  sqlite3 /opt/alist/data/data.db <<EOF
INSERT OR REPLACE INTO x_storages VALUES(99999,'/©️ $version-$app_ver',0,'Alias',30,'work','{"paths":"/每日更新"}','','2022-11-12 13:05:12+00:00',0,'','','',0,'302_redirect','');
EOF
}

# 主流程
log_info "Starting database operations"
upgrade_h2
restore_database

if is_initialized; then
  log_info "Already initialized, running update tasks"
  update_movie
else
  log_info "Running first-time xiaoya initialization"
  init_directories
  setup_symlinks
  xiaoya_first_init
  mark_initialized
fi

# 解压资源包（每次启动都检查）
extract_resource_zips

# 初始化 115 索引（仅当 /data/index115 不存在）
seed_index115

# 更新 xiaoya 数据
update_xiaoya_data

log_info "Xiaoya initialization completed successfully"
