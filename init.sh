#!/bin/sh

mkdir -p /var/lib/pxg /www/cgi-bin /index /opt/atv/log /data/atv /data/index /data/backup
if [ -d /index ]; then
  rm -rf /index
fi
ln -sf /data/index /
ln -sf /data/config .
cd /var/lib/pxg
unzip -q /var/lib/data.zip
mv data.db /opt/alist/data/data.db
mv search /www/cgi-bin/search
mv sou /www/cgi-bin/sou
mv header.html /www/cgi-bin/header.html

sed '/location \/dav/i\    location ~* alist {\n        deny all;\n    }\n' nginx.conf >/etc/nginx/http.d/default.conf

mv mobi.tgz /www/mobi.tgz
cd /www/
tar zxf mobi.tgz
rm mobi.tgz

sqlite3 /opt/alist/data/data.db ".read /update.sql"

cd /tmp/

wget --user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppelWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36" -T 10 -t 2 http://docker.xiaoya.pro/update/tvbox.zip
if [ ! -f tvbox.zip ]; then
  wget -T 20 -t 2 http://cdn.har01d.cn/tvbox/data/tvbox.zip
fi
unzip -q -o tvbox.zip
rm tvbox.zip
if [ -f /data/my.json ]; then
  rm /www/tvbox/my.json
  ln -s /data/my.json /www/tvbox/my.json
fi

if [ -f /data/iptv.m3u ]; then
  ln -s /data/iptv.m3u /www/tvbox/iptv.m3u
fi

rm -f index.zip index.txt version.txt update.zip

wget --user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppelWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36" -T 10 -t 2 -q http://docker.xiaoya.pro/update/version.txt
if [ ! -f version.txt ]; then
  wget -T 10 -t 2 http://d.har01d.cn/version.txt
fi
wget --user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppelWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36" -T 10 -t 2 http://docker.xiaoya.pro/update/update.zip
if [ ! -f update.zip ]; then
  wget -T 20 -t 2 http://cdn.har01d.cn/tvbox/data/update.zip
fi
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

  sed -i '/v3.9.2/d' update.sql
  sed -i '/alist.xiaoya.pro/d' update.sql

  sqlite3 /opt/alist/data/data.db <<EOF
drop table x_storages;
drop table x_meta;
drop table x_setting_items;
update x_users set permission = 368 where id = 2;
.read update.sql
EOF

  echo "$(date) update database successfully"
  opentoken_url=$(cat opentoken_url.txt)
  sed -i "s#https://api.nn.ci/alist/ali_open/token#$opentoken_url#" /opt/alist/data/config.json
  rm update.zip update.sql opentoken_url.txt
fi

sqlite3 /opt/alist/data/data.db 'delete from x_storages where driver="AList V3";'

if [ ! -f version.txt ]; then
  echo "Failed to download version.txt file, the index file upgrade process has aborted"
else
  remote=$(head -n1 version.txt)
  if [ ! -f /data/index/version.txt ]; then
    echo 0.0.0 >/data/index/version.txt
  fi
  local=$(head -n1 /data/index/version.txt)
  latest=$(printf "$remote\n$local\n" | sort -r | head -n1)
  if [ "$remote" = "$local" ]; then
    echo "$(date) current index file version is updated, no need to upgrade"
  elif [ "$remote" = "$latest" ]; then
    wget --user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppelWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36" -T 10 -t 2 http://docker.xiaoya.pro/update/index.zip
    if [ ! -f index.zip ]; then
      wget -T 30 -t 2 http://cdn.har01d.cn/tvbox/data/index.zip
    fi
    if [ ! -f index.zip ]; then
      echo "Failed to download index compressed file, the index file upgrade process has aborted"
    else
      unzip -o -q -P abcd index.zip
      cat index.video.txt index.book.txt index.music.txt index.non.video.txt >/data/index/index.txt
      mv index*.txt /data/index/
      echo "$(date) update index successfully, your new version.txt is $remote"
      echo "$remote" >/data/index/version.txt
    fi
  else
    echo "$(date) your current version.txt is updated, no need to downgrade"
    echo "$remote" >/data/index/version.txt
  fi
  rm -f index.* update.* version.txt
fi

#wget http://d.har01d.cn/cat_open.zip -O cat_open.zip && \
#unzip cat_open.zip -d /www/tvbox/

version=$(head -n1 /docker.version)
app_ver=$(head -n1 /opt/atv/data/app_version)
sqlite3 /opt/alist/data/data.db <<EOF
INSERT INTO x_storages VALUES(20000,'/©️ $version-$app_ver',0,'Alias',30,'work','{"paths":"/每日更新"}','','2022-11-12 13:05:12+00:00',0,'','','',0,'302_redirect','');
EOF

LOCAL="0.0"
if [ -f /data/atv/base_version ]; then
  LOCAL=$(head -n 1 </data/atv/base_version)
fi
REMOTE=$(curl -fsSL http://d.har01d.cn/base_version | head -n 1)
echo "movie base version: $LOCAL $REMOTE"
if [ "$LOCAL" != "$REMOTE" ]; then
  wget http://cdn.har01d.cn/tvbox/data/data.zip -O data.zip && \
  unzip -q -o data.zip -d /tmp && \
  cp /tmp/data/data.sql /data/atv/
fi
