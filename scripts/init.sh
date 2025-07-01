#!/bin/sh

init_version=$(head -n 1 "/opt/alist/data/.init" 2>/dev/null || echo "")

restore_database() {
  if [ -f "/data/database.zip" ]; then
    echo "=== restore database ==="
    rm -f /data/atv.mv.db /data/atv.trace.db
    /jre/bin/java -cp /opt/atv/BOOT-INF/lib/h2-*.jar org.h2.tools.RunScript -url jdbc:h2:/data/atv -user sa -password password -script /data/database.zip -options compression zip
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

  [ -f /opt/alist/data/config.json ] || cp /alist.json /opt/alist/data/config.json
  sed -i 's/127.0.0.1/0.0.0.0/' /opt/alist/data/config.json

  cd /opt/alist
  /opt/alist/alist admin
  cd /www/

  gh_proxy=$(head -n 1 "/data/github_proxy.txt" 2>/dev/null || echo "")
  wget -T 30 -t 2 ${gh_proxy}https://raw.githubusercontent.com/xiaoyaliu00/data/main/tvbox.zip -O tvbox.zip || \
  wget -t 3 http://har01d.org/tvbox.zip -O tvbox.zip || \
  cp /tvbox.zip ./

  unzip -q -o tvbox.zip

  rm -f mobi.tgz tvbox.zip index.zip index.txt version.txt update.zip
  echo "1" > /opt/alist/data/.init
}

upgrade_h2() {
  if [ -f /data/h2.version.txt ]; then
    return
  fi
  echo "try to upgrade database"
  file=/opt/atv/data/data
  [ -f /data/atv.mv.db ] && file=/data/atv
  echo "export database $file"
  /jre/bin/java -cp /h2-2.1.214.jar org.h2.tools.Script \
  -url jdbc:h2:file:$file \
  -user sa -password password \
  -script backup.sql && \
  echo "import database" && \
  rm -f ${file}.mv.db ${file}.trace.db && \
  /jre/bin/java -cp /opt/atv/BOOT-INF/lib/h2-2.3.232.jar org.h2.tools.RunScript \
  -url jdbc:h2:file:$file \
  -user sa -password password \
  -script backup.sql && \
  echo "upgraded h2 to 2.3.232" && \
  echo "2.3.232" > /data/h2.version.txt
}

echo "Install mode: $INSTALL"
cat /app_version
date
uname -mor

upgrade_h2
restore_database
if [ "$init_version" = "1" ]; then
  echo "已经初始化成功"
else
  init
fi

if [ ! -d /www/cat ]; then
  echo "unzip cat.zip"
  mkdir -p /www/cat
  unzip -q -o /cat.zip -d /www/cat
fi
[ -d /data/cat ] && cp -r /data/cat/* /www/cat/

[ ! -f /data/pg.zip ] && cp /pg.zip /data/pg.zip
if [ ! -d /www/pg ]; then
  echo "unzip pg.zip"
  mkdir -p /www/pg
  unzip -q -o /data/pg.zip -d /www/pg
fi
[ -d /data/pg ] && cp -r /data/pg/* /www/pg/

[ ! -f /data/zx.zip ] && cp /zx.zip /data/zx.zip
if [ ! -d /www/zx ]; then
  echo "unzip zx.zip"
  mkdir -p /www/zx
  unzip -q -o /data/zx.zip -d /www/zx
fi
