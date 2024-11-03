#!/bin/sh

update_movie() {
  LOCAL="0.0"
  if [ -f /data/atv/base_version ]; then
    LOCAL=$(head -n 1 </data/atv/base_version)
  fi
  REMOTE=$(head -n 1 </base_version)
  echo "movie base version: $LOCAL $REMOTE"
  if [ "$LOCAL" != "$REMOTE" ]; then
    echo "upgrade movie data"
    unzip -q -o /data.zip -d /data/atv/
    cp /base_version /tmp/
    rm -f /data/atv/sql/*.sql
  fi
}

restore_database() {
  if [ -f "/data/database.zip" ]; then
    echo "=== restore database ==="
    rm -f /data/atv.mv.db /data/atv.trace.db
    java -cp /opt/atv/BOOT-INF/lib/h2-*.jar org.h2.tools.RunScript -url jdbc:h2:/data/atv -user sa -password password -script /data/database.zip -options compression zip
    rm -f /data/database.zip /data/atv/base_version /data/atv/movie_version
  fi
}

init() {
  mkdir -p /var/lib/pxg /www/cgi-bin /index /data/atv /data/index /data/backup
  if [ -d /index ]; then
    rm -rf /index
  fi
  [ -h /data/log/log ] && unlink /data/log/log
  ln -sf /data/index /
  ln -sf /data/config .
  cd /var/lib/pxg
  unzip -q /var/lib/data.zip
  mv data.db /opt/alist/data/data.db
  sed -i 's!/"$after"!"$after"!' search
  mv search /www/cgi-bin/search
  mv sou /www/cgi-bin/sou
  mv whatsnew /www/cgi-bin/whatsnew
  mv header.html /www/cgi-bin/header.html

  sed -i "s/127.0.0.1/0.0.0.0/" /opt/alist/data/config.json
  sed '/location \/dav/i\    location ~* alist {\n        deny all;\n    }\n' nginx.conf >/etc/nginx/http.d/default.conf

  mv mobi.tgz /www/mobi.tgz
  cd /www/
  tar zxf mobi.tgz
  rm mobi.tgz

  sqlite3 /opt/alist/data/data.db ".read /update.sql"

  wget -T 30 -t 2 https://raw.githubusercontent.com/xiaoyaliu00/data/main/tvbox.zip -O tvbox.zip || \
  wget -T 30 -t 2 http://har01d.org/tvbox.zip -O tvbox.zip || \
  cp /tvbox.zip ./

  unzip -q -o tvbox.zip
  if [ -f /data/my.json ]; then
    rm /www/tvbox/my.json
    ln -s /data/my.json /www/tvbox/my.json
  fi

  if [ -f /data/iptv.m3u ]; then
    ln -s /data/iptv.m3u /www/tvbox/iptv.m3u
  fi

  rm -f tvbox.zip index.zip index.txt version.txt update.zip

  update_movie
}

echo "Install mode: $INSTALL"
cat data/app_version
version=$(head -n1 /docker.version)
echo "xiaoya version: $version"
uname -mor
date

restore_database
if [ -f /opt/alist/data/data.db ]; then
  update_movie
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

cd /tmp/

wget -T 30 -t 2 https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt -O version.txt || \
wget -T 10 -t 2 http://har01d.org/version.txt -O version.txt

wget -T 30 -t 2 https://raw.githubusercontent.com/xiaoyaliu00/data/main/update.zip -O update.zip || \
wget -T 30 -t 2 http://har01d.org/update.zip -O update.zip

if [ ! -f update.zip ]; then
  echo "Failed to download update database file, the database upgrade process has aborted"
else
  unzip -o -q -P abcd update.zip
  entries=$(grep -c 'INSERT INTO x_storages' update.sql)
  echo "$(date) total $entries records"
  if [ -f /opt/alist/data/data.db-shm ]; then
    rm /opt/alist/data/data.db-shm
  fi

  if [ -f /opt/alist/data/data.db-wal ]; then
    rm /opt/alist/data/data.db-wal
  fi

  sed -i 's/v3.9.2/v3.25.1/' update.sql
  sed -i 's/pass_code/share_pwd/' update.sql

  sqlite3 /opt/alist/data/data.db <<EOF
drop table x_storages;
drop table x_meta;
drop table x_setting_items;
.read update.sql
EOF

  echo "$(date) update database successfully"
  opentoken_url=$(cat opentoken_url.txt)
  sed -i "s#https://api.nn.ci/alist/ali_open/token#$opentoken_url#" /opt/alist/data/config.json
  rm -f update.zip update.sql opentoken_url.txt
fi

if [ ! -f version.txt ]; then
  echo "Failed to download version.txt file, the index file upgrade process has aborted"
else
  remote=$(head -n1 version.txt)
  if [ ! -f /data/index/version.txt ]; then
    echo 0.0.0 >/data/index/version.txt
  fi
  local=$(head -n1 /data/index/version.txt)
  echo "index version: $local $remote"
  latest=$(printf "$remote\n$local\n" | sort -r | head -n1)
  if [ "$remote" = "$local" ]; then
    echo "$(date) current index file version is updated, no need to upgrade"
  elif [ "$remote" = "$latest" ]; then
    wget -T 30 -t 2 https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip -O index.zip || \
    wget -T 40 -t 2 http://har01d.org/index.zip -O index.zip

    if [ ! -f index.zip ]; then
      echo "Failed to download index compressed file, the index file upgrade process has aborted"
    else
      unzip -o -q -P abcd index.zip
      cat index.video.txt index.book.txt index.music.txt index.non.video.txt >/data/index/index.txt
      mv index*.txt /data/index/
      echo "$(date) update index successfully, your new version is $remote"
      echo "$remote" >/data/index/version.txt
    fi
  else
    echo "$(date) your current version is updated, no need to downgrade"
    echo "$remote" >/data/index/version.txt
  fi
  rm -f index.* update.* version.txt
fi

LOCAL="0.0"
if [ -f /data/index/share_version ]; then
  LOCAL=$(head -n 1 </data/index/share_version)
fi
unzip -q -o /index.share.zip -d /tmp
REMOTE=$(head -n 1 </tmp/share_version)
echo "share index version: $LOCAL $REMOTE"
if [ "$LOCAL" != "$REMOTE" ]; then
  echo "upgrade share index"
  mv /tmp/index.share.txt /data/index/index.share.txt
  mv /tmp/share_version /data/index/share_version
  grep -v "/🈴我的阿里分享/" /data/index/index.video.txt >/data/index/index.video.txt.1
  grep -v "/🈴我的阿里分享/" /data/index/index.txt >/data/index/index.txt.1
  mv /data/index/index.video.txt.1 /data/index/index.video.txt
  mv /data/index/index.txt.1 /data/index/index.txt
  cat /data/index/index.share.txt >> /data/index/index.video.txt
  cat /data/index/index.share.txt >> /data/index/index.txt
fi
rm -f /tmp/index.share.txt

app_ver=$(head -n1 /opt/atv/data/app_version)
sqlite3 /opt/alist/data/data.db <<EOF
INSERT INTO x_storages VALUES(20000,'/©️ $version-$app_ver',0,'Alias',30,'work','{"paths":"/每日更新"}','','2022-11-12 13:05:12+00:00',0,'','','',0,'302_redirect','');
EOF
