#!/bin/sh

init() {
  mkdir -p /var/lib/pxg /www/cgi-bin /data/atv /data/index /data/backup
  if [ -d /index ]; then
    rm -rf /index
  fi
  ln -sf /data/index /
  ln -sf /data/config .
  cd /var/lib/pxg
  unzip -q /var/lib/data.zip
  sed -i 's!/"$after"!"$after"!' search
  mv search /www/cgi-bin/search
  mv sou /www/cgi-bin/sou
  mv header.html /www/cgi-bin/header.html
  mv nginx.conf /etc/nginx/http.d/default.conf

  mv mobi.tgz /www/mobi.tgz
  cd /www/
  tar zxf mobi.tgz
  rm mobi.tgz

  sed -i 's/127.0.0.1/0.0.0.0/' /opt/alist/data/config.json

  sqlite3 /opt/alist/data/data.db ".read /alist.sql"

  wget --user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppelWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36" -T 30 -t 2 http://docker.xiaoya.pro/update/tvbox.zip ||
    wget -T 30 -t 2 http://data.har01d.cn/tvbox.zip -O tvbox.zip

  unzip -q -o tvbox.zip
  if [ -f /data/my.json ]; then
    rm /www/tvbox/my.json
    ln -s /data/my.json /www/tvbox/my.json
  fi

  if [ -f /data/iptv.m3u ]; then
    ln -s /data/iptv.m3u /www/tvbox/iptv.m3u
  fi

  rm -f tvbox.zip index.zip index.txt version.txt update.zip
}

cat data/app_version
date

if [ -f /opt/alist/data/data.db ]; then
  echo "已经初始化成功"
else
  init
fi
