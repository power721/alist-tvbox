#!/bin/sh

mkdir -p /var/lib/pxg
cd /var/lib/pxg
unzip -q /var/lib/data.zip
mv data.db /opt/alist/data/data.db
mv config.json /opt/alist/data/config.json
mkdir -p /www/cgi-bin
mkdir -p /index
mv search /www/cgi-bin/search
mv header.html /www/cgi-bin/header.html
sed '/location \/dav/i\    location ~* alist {\n        deny all;\n    }\n' nginx.conf >/etc/nginx/http.d/default.conf
mv mobi.tgz /www/mobi.tgz
cd /www/
tar zxf mobi.tgz
rm mobi.tgz
wget --user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppelWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36" -T 5 -t 2 http://docker.xiaoya.pro/update/tvbox.zip
unzip -q -o tvbox.zip
rm tvbox.zip
if [ -f /data/my.json ]; then
  rm /www/tvbox/my.json
  ln -s /data/my.json /www/tvbox/my.json
fi

if [ -f /data/iptv.m3u ]; then
  ln -s /data/iptv.m3u /www/tvbox/iptv.m3u
fi

cd /tmp/

rm -f index.zip index.txt version.txt update.zip

wget --user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppelWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36" -T 5 -t 2 -q http://docker.xiaoya.pro/update/version.txt
wget --user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppelWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36" -T 5 -t 2 http://docker.xiaoya.pro/update/update.zip
if [ ! -f update.zip ]; then
  echo "Failed to download update database file, the database upgrade process has aborted"
  exit
else
  unzip -o -q -P abcd update.zip
  entries=$(grep -c 'INSERT INTO x_storages' update.sql)
  echo $(date) "total" $entries "records"
  if [ -f /opt/alist/data/data.db-shm ]; then
    rm /opt/alist/data/data.db-shm
  fi

  if [ -f /opt/alist/data/data.db-wal ]; then
    rm /opt/alist/data/data.db-wal
  fi

sqlite3 /opt/alist/data/data.db <<EOF
select value from x_setting_items where key='version';
EOF

  pass=$(tr -dc '_A-Za-z0-9' </dev/urandom | head -c 32)
  sqlite3 /opt/alist/data/data.db <<EOF
drop table x_storages;
drop table x_meta;
drop table x_setting_items;
update x_users set password = "$pass" where id = 1;
update x_users set permission  = 368 where id = 2;
.read update.sql
EOF
  echo $(date) "update database succesfully, your new version is" $remote
  opentoken_url=$(cat opentoken_url.txt)
  sed -i "s#https://api.nn.ci/alist/ali_open/token#$opentoken_url#" /opt/alist/data/config.json
  rm update.zip update.sql opentoken_url.txt
fi

if [ ! -f version.txt ]; then
  echo "Failed to download version.txt file, the index file upgrade process has aborted"
else
  remote=$(head -n1 version.txt)
  if [ ! -f /version.txt ]; then
    echo 0.0.0 >/version.txt
  fi
  local=$(head -n1 /version.txt)
  latest=$(printf "$remote\n$local\n" | sort -r | head -n1)
  if [ $remote = $local ]; then
    echo $(date) "current index file version is updated, no need to upgrade"
    exit
  elif [ $remote = $latest ]; then
    wget --user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppelWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36" -T 5 -t 2 http://docker.xiaoya.pro/update/index.zip
    if [ ! -f index.zip ]; then
      echo "Failed to download index compressed file, the index file upgrade process has aborted"
      exit
    else
      unzip -o -q -P abcd index.zip
      cat index.video.txt index.book.txt index.music.txt index.non.video.txt >/index/index.txt
      mv index*.txt /index/
      echo $(date) "update index succesfully, your new version.txt is" $remote
      echo $remote >/version.txt
    fi

  else
    echo $(date) "your current version.txt is updated, no need to downgrade"
    echo $remote >/version.txt
  fi
  rm index.* 2>/dev/null
  rm update.* 2>/dev/null
  rm version.txt
fi

exec "$@"
