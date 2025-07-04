#!/usr/bin/env bash
trap 'log "$RED" "脚本被中断，建议下载脚本再运行"; exit 130' INT TERM
set -euo pipefail

# ========== 颜色定义 ==========
RED='\e[31m'
GREEN='\e[32m'
YELLOW='\e[33m'
BLUE='\e[34m'
CYAN='\e[36m'
NC='\e[0m'

# ========== 变量定义 ==========
SCRIPT_VERSION="v1.1"
LOG_FILE="/opt/atv/log/upgrade.log"
mkdir -p "$(dirname "$LOG_FILE")"
echo "===== 升级日志 $(date '+%F %T') =====" > "$LOG_FILE"

# ========== 打印函数 ==========
log() {
  local COLOR="$1"
  local MSG="$2"
  echo -e "${COLOR}${MSG}${NC}" | tee -a "$LOG_FILE"
}

# ========== 欢迎信息 ==========
log "$CYAN" "=== AList TvBox 一键升级脚本 ${SCRIPT_VERSION} ==="

if ! sudo -v; then
  log "$RED" "需要 sudo 权限才能运行此脚本，请确保已配置。"
  exit 1
fi

FORCE=false
[[ "${1:-}" == "--force" ]] && FORCE=true

# ========== 安装 SQLite3 ==========
check_and_install_sqlite3() {
  if ! command -v sqlite3 &>/dev/null; then
    log "$RED" "SQLite3 未安装，正在自动安装..."

    if [ -f /etc/debian_version ]; then
      sudo apt update && sudo apt install -y sqlite3
    elif [ -f /etc/redhat-release ]; then
      sudo yum install -y sqlite3
    elif [ -f /etc/arch-release ]; then
      sudo pacman -Sy --noconfirm sqlite
    elif [ -f /etc/alpine-release ]; then
      sudo apk add sqlite
    else
      log "$RED" "无法自动安装 SQLite3，请手动安装！"
      exit 1
    fi

    if ! command -v sqlite3 &>/dev/null; then
      log "$RED" "SQLite3 安装失败，请手动安装！"
      exit 1
    else
      log "$GREEN" "SQLite3 安装成功！"
    fi
  fi
}
check_and_install_sqlite3

# ========== 检查运行中容器 ==========
if command -v docker &>/dev/null; then
  running=$(docker ps --format '{{.Names}}' | grep -E '^(xiaoya-tvbox|alist-tvbox)$' || true)
  if [ -n "$running" ] && [ "$FORCE" = false ]; then
    log "$RED" "检测到以下容器正在运行:\n$running\n请先停止这些容器再执行脚本。"
    exit 1
  elif [ -n "$running" ]; then
    log "$YELLOW" "检测到运行中的容器，尝试停止..."
    for name in $running; do
      log "$YELLOW" "Stopping container: $name"
      docker stop "$name"
    done
  fi
fi

# ========== 获取远程版本 ==========
VERSION1=$(curl -sf http://d.har01d.cn/app.version.txt)
[ -z "$VERSION1" ] && log "$RED" "获取 AList TvBox 版本失败！" && exit 1

VERSION2=$(curl -sf http://d.har01d.cn/alist.version.txt)
[ -z "$VERSION2" ] && log "$RED" "获取 Power AList 版本失败！" && exit 1

# ========== 获取本地版本 ==========
LOCAL_VERSION1="0.0.0"
LOCAL_VERSION2="0.0.0"
APP=atv
GROUP=$(id -gn)
USER=$(id -un)

[ -f /opt/atv/data/app_version ] && LOCAL_VERSION1=$(head -n 1 /opt/atv/data/app_version)
[ -f /opt/atv/alist/data/version ] && LOCAL_VERSION2=$(head -n 1 /opt/atv/alist/data/version)

if [ "$LOCAL_VERSION1" = "$VERSION1" ] && [ "$LOCAL_VERSION2" = "$VERSION2" ]; then
  log "$GREEN" "✅ 当前已是最新版本："
  log "$BLUE" "   AList TvBox: $VERSION1"
  log "$BLUE" "   Power AList: $VERSION2"
  if [ "$FORCE" = true ]; then
    log "$CYAN" "启动服务 atv.service..."
    nohup sudo systemctl restart atv.service > /opt/atv/log/systemctl-restart.log 2>&1 &
    sleep 5
    sudo systemctl status atv.service -n 20 --no-pager | tee -a "$LOG_FILE"
    if ! sudo systemctl is-active --quiet atv.service; then
      log "$RED" "❌ atv.service 启动失败！请查看日志：sudo journalctl -u atv.service -n 50 --no-pager"
      exit 1
    fi
    log "$GREEN" "✅ 服务启动成功。"
  fi
  exit 0
fi

log "$CYAN" "当前用户: $USER:$GROUP"

# ========== 创建目录结构 ==========
sudo mkdir -p /opt/atv/{config,scripts,index,log,data/atv,data/backup,www/{cat,pg,zx,tvbox,files},alist/{data,log}}
sudo chown -R ${USER}:${GROUP} /opt/atv

conf=/opt/atv/config/application-production.yaml
if [ ! -f "$conf" ]; then
  sudo tee "$conf" > /dev/null <<EOF
spring:
  datasource:
    url: jdbc:h2:file:/opt/atv/data/data
EOF
fi

# ========== 使用临时目录下载并解压 ==========
TMPDIR=$(mktemp -d)
cd "$TMPDIR"

[ "$LOCAL_VERSION1" != "$VERSION1" ] && {
  log "$YELLOW" "升级 AList TvBox: $LOCAL_VERSION1 -> $VERSION1"
  wget http://har01d.org/atv.tgz -O atv.tgz && tar xf atv.tgz
}

[ "$LOCAL_VERSION2" != "$VERSION2" ] && {
  log "$YELLOW" "升级 Power AList: $LOCAL_VERSION2 -> $VERSION2"
  wget http://har01d.org/alist.tgz -O alist.tgz && tar xf alist.tgz
}

# ========== 生成 systemd 服务 ==========
cat <<EOF > atv.service
[Unit]
Description=atv API
After=syslog.target

[Service]
User=${USER}
Group=${GROUP}
Environment="NATIVE=true"
WorkingDirectory=/opt/atv
ExecStart=/opt/atv/atv --spring.profiles.active=standalone,production
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

sudo mv atv.service /etc/systemd/system/atv.service
sudo systemctl daemon-reload
sudo systemctl stop atv.service || echo ""

# ========== 安装 AList TvBox ==========
[ "$LOCAL_VERSION1" != "$VERSION1" ] && {
  log "$YELLOW" "安装新版本 ATV..."
  sudo rm -f /opt/atv/atv
  sudo install -m 755 -o "$USER" -g "$GROUP" atv /opt/atv/atv
  echo "$VERSION1" > /opt/atv/data/app_version
}

# ========== 安装 Power AList ==========
[ "$LOCAL_VERSION2" != "$VERSION2" ] && {
  log "$YELLOW" "安装新版本 AList..."
  sudo rm -f /opt/atv/alist/alist
  sudo install -m 755 -o "$USER" -g "$GROUP" alist /opt/atv/alist/alist
  cd /opt/atv/alist
  ./alist admin > /opt/atv/log/init.log 2>&1
  echo "$VERSION2" > /opt/atv/alist/data/version
}

# ========== 启动服务 ==========
sudo systemctl enable atv.service
sudo systemctl restart atv.service &
sleep 2

log "$CYAN" "启动服务 atv.service..."
nohup sudo systemctl restart atv.service > /opt/atv/log/systemctl-restart.log 2>&1 &
sleep 5
sudo systemctl status atv.service -n 20 --no-pager | tee -a "$LOG_FILE"
if ! sudo systemctl is-active --quiet atv.service; then
  log "$RED" "❌ atv.service 启动失败！请查看日志：sudo journalctl -u atv.service -n 50 --no-pager"
  exit 1
fi
log "$GREEN" "✅ 服务启动成功。"

log "$GREEN" "✅ 升级完成！服务已启动。"
log "$CYAN" "👉 请访问：http://localhost:4567"
log "$CYAN" "👉 查看服务状态：sudo systemctl status atv.service"
log "$CYAN" "👉 查看启动日志：sudo journalctl -u atv.service -n 50"
log "$CYAN" "👉 日志文件：$LOG_FILE"

rm -rf "$TMPDIR"
