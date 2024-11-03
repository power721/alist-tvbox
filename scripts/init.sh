#!/bin/sh

restore_database() {
  if [ -f "/data/database.zip" ]; then
    echo "=== restore database ==="
    rm -f /data/atv.mv.db /data/atv.trace.db
    java -cp /opt/atv/BOOT-INF/lib/h2-*.jar org.h2.tools.RunScript -url jdbc:h2:/data/atv -user sa -password password -script /data/database.zip -options compression zip
    rm -f /data/database.zip
  fi
}

init() {
  mkdir -p /var/lib/pxg /www/cgi-bin /data/atv /data/index /data/backup
  if [ -d /index ]; then
    rm -rf /index
  fi
  ln -sf /data/index /
  ln -sf /data/config .
  cd /var/lib/pxg
  unzip -q /var/lib/data.zip
  mv search /www/cgi-bin/search
  mv sou /www/cgi-bin/sou
  mv header.html /www/cgi-bin/header.html
  mv nginx.conf /etc/nginx/http.d/default.conf

  mv mobi.tgz /www/mobi.tgz
  cd /www/
  tar zxf mobi.tgz

  sed -i 's/127.0.0.1/0.0.0.0/' /opt/alist/data/config.json

  sqlite3 /opt/alist/data/data.db ".read /alist.sql"

  wget -T 30 -t 2 https://raw.githubusercontent.com/xiaoyaliu00/data/main/tvbox.zip -O tvbox.zip || \
  wget -T 30 -t 2 http://har01d.org/tvbox.zip -O tvbox.zip || \
  cp /tvbox.zip ./

  unzip -q -o tvbox.zip

  rm -f mobi.tgz tvbox.zip index.zip index.txt version.txt update.zip
}

echo "Install mode: $INSTALL"
cat data/app_version
date
uname -mor

restore_database
if [ -f /opt/alist/data/data.db ]; then
  echo "已经初始化成功"
else
  init
fi

if [ ! -d /www/cat ]; then
  echo "unzip cat.zip"
  mkdir /www/cat
  unzip -q -o /cat.zip -d /www/cat
fi
[ -d /data/cat ] && cp -r /data/cat/* /www/cat/

[ ! -f /data/pg.zip ] && cp /pg.zip /data/pg.zip
if [ ! -d /www/pg ]; then
  echo "unzip pg.zip"
  mkdir /www/pg
  unzip -q -o /data/pg.zip -d /www/pg
fi
[ -d /data/pg ] && cp -r /data/pg/* /www/pg/

[ ! -f /data/zx.zip ] && cp /zx.zip /data/zx.zip
if [ ! -d /www/zx ]; then
  echo "unzip zx.zip"
  mkdir /www/zx
  unzip -q -o /data/zx.zip -d /www/zx
fi
