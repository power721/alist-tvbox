#!/usr/bin/env bash
set -euo pipefail

check_and_install_sqlite3() {
    if ! command -v sqlite3 &> /dev/null; then
        echo -e "\e[31mSQLite3 未安装，正在自动安装...\e[0m"

        if [ -f /etc/debian_version ]; then
            sudo apt update && sudo apt install -y sqlite3
        elif [ -f /etc/redhat-release ]; then
            sudo yum install -y sqlite3
        elif [ -f /etc/arch-release ]; then
            sudo pacman -Sy --noconfirm sqlite
        elif [ -f /etc/alpine-release ]; then
            sudo apk add sqlite3
        else
            echo -e "\e[31m无法自动安装 SQLite3，请手动安装！\e[0m"
            exit 1
        fi

        if ! command -v sqlite3 &> /dev/null; then
            echo -e "\e[31mSQLite3 安装失败，请手动安装！\e[0m"
            exit 1
        else
            echo -e "\e[32mSQLite3 安装成功！\e[0m"
        fi
    fi
}

check_and_install_sqlite3

if docker ps | egrep 'xiaoya-tvbox|alist-tvbox'; then
  if [ $# -gt 0 ]; then
    echo "Stop docker container"
    for name in xiaoya-tvbox alist-tvbox; do
      if docker ps --format '{{.Names}}' | grep -q "^${name}$"; then
        echo "Stopping container: $name"
        docker stop "$name"
      fi
    done
  else
    echo -e "\e[31m请停止Docker容器再运行！\e[0m"
    exit 1
  fi
fi

VERSION1=$(curl -s http://d.har01d.cn/app.version.txt)
VERSION2=$(curl -s http://d.har01d.cn/alist.version.txt)

LOCAL_VERSION1="0.0.0"
LOCAL_VERSION2="0.0.0"

GROUP=$(id -rgn $USER)
APP=atv

if [ -f /opt/atv/data/app_version ]; then
  LOCAL_VERSION1=$(head -n 1 </opt/atv/data/app_version)
fi

if [ -f /opt/atv/alist/data/version ]; then
  LOCAL_VERSION2=$(head -n 1 </opt/atv/alist/data/version)
fi

if [ "$LOCAL_VERSION1" = "$VERSION1" ] && [ "$LOCAL_VERSION2" = "$VERSION2" ] ; then
    echo "Already latest version"
    echo "Power AList: $VERSION2"
    echo "AList TvBox: $VERSION1"
    if [ $# -gt 0 ]; then
      echo "Restarting atv.service"
      sudo systemctl restart atv.service
      sleep 3
      sudo systemctl status atv.service
    fi
    exit 0
fi

echo "User: $USER:$GROUP"

sudo mkdir -p /opt/atv/alist/{data,log}
sudo mkdir -p /opt/atv/{config,scripts,index,log}
sudo mkdir -p /opt/atv/data/{atv,backup}
sudo mkdir -p /opt/atv/www/{cat,pg,zx,tvbox,files}
sudo chown -R ${USER}:${GROUP} /opt/atv

conf=/opt/atv/config/application-production.yaml
if [ ! -f $conf ]; then
cat <<EOF >atv.yaml
spring:
  datasource:
    url: jdbc:h2:file:/opt/atv/data/data
EOF
sudo mv atv.yaml $conf
fi

# TODO: download scripts

[ "$LOCAL_VERSION1" != "$VERSION1" ] && \
echo "Upgrade AList TvBox from $LOCAL_VERSION1 to $VERSION1" && \
wget http://har01d.org/atv.tgz -O atv.tgz && \
tar xf atv.tgz && rm -f atv.tgz

[ "$LOCAL_VERSION2" != "$VERSION2" ] && \
echo "Upgrade Power AList from $LOCAL_VERSION2 to $VERSION2" && \
wget http://har01d.org/alist.tgz -O alist.tgz && \
tar xf alist.tgz && rm -f alist.tgz

cat <<EOF > atv.service
[Unit]
Description=atv API
After=syslog.target

[Service]
User=${USER}
Group=${GROUP}
WorkingDirectory=/opt/atv
ExecStart=/opt/atv/atv --spring.profiles.active=standalone,production
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

sudo mv atv.service /etc/systemd/system/atv.service
sudo systemctl daemon-reload
sudo systemctl stop atv.service

[ "$LOCAL_VERSION1" != "$VERSION1" ] && \
echo "upgrade ATV" && \
sudo rm -f /opt/atv/atv && \
sudo mv atv /opt/atv/atv && \
sudo chown ${USER}:${GROUP} /opt/atv/atv && \
chmod +x /opt/atv/atv && \
echo $VERSION1 > /opt/atv/data/app_version

[ "$LOCAL_VERSION2" != "$VERSION2" ] && \
echo "upgrade AList" && \
sudo rm -f /opt/atv/alist/alist && \
sudo mv alist /opt/atv/alist/alist && \
cd /opt/atv/alist/ && \
sudo chown -R ${USER}:${GROUP} /opt/atv/alist  && \
chmod +x alist && \
./alist admin > /opt/atv/log/init.log 2>&1 && \
echo $VERSION2 > /opt/atv/alist/data/version

sudo systemctl enable atv.service
sudo systemctl restart atv.service
sleep 3
sudo systemctl status atv.service
