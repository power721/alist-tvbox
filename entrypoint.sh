#!/bin/sh

if [ -f /data/proxy.txt ]; then
  proxy_url=$(head -n1 /data/proxy.txt)
  export HTTP_PROXY=$proxy_url
  export HTTPS_PROXY=$proxy_url
  export no_proxy="*.aliyundrive.com"
fi

chmod a+x /init.sh /index.sh

/init.sh

/bin/busybox-extras httpd -p "$1" -h /www
/usr/sbin/nginx
shift

version=$(head -n1 /docker.version)
sqlite3 /opt/alist/data/data.db <<EOF
INSERT INTO x_storages VALUES(20000,'/©️ $version',0,'AList V3',30,'work','{"root_folder_path":"/安装，配置，修复 xiaoya docker 指南/打赏码，谢谢你的支持.jpg","url":"http://alist.xiaoya.pro","password":"","access_token":""}','','2022-11-12 13:05:12.467024193+00:00',0,'','','',0,'302_redirect','');
EOF

LOCAL="0.0"
if [ -f /data/atv/movie_version ]; then
  LOCAL=$(head -n 1 </data/atv/movie_version)
fi
REMOTE=$(curl -fsSL http://d.har01d.cn/movie_version | head -n 1)
latest=$(printf "$REMOTE\n$LOCAL\n" | sort -r | head -n1)
echo "local movie data version: ${LOCAL}, remote version: ${REMOTE}"
if [ "$LOCAL" = "$REMOTE" ]; then
  echo "the movie data is updated"
elif [ "$latest" = "$REMOTE" ]; then
  echo "download data.zip" && \
  wget http://d.har01d.cn/data.zip -O data.zip && \
  unzip -q -o data.zip -d /tmp && \
  cp /tmp/data/movie_version /data/atv/ && \
  cp /tmp/data/data.sql /data/atv/ && \
  cat /data/atv/movie_version && \
  rm -f /tmp/data.zip
  cp /data/atv.mv.db /data/backup/atv.mv.db
else
  echo "use local movie data"
fi

if [ -f /data/cmd.sql ]; then
  echo "add cmd.sql"
  wc /data/cmd.sql
  cat /data/cmd.sql >> /data/atv/data.sql
  rm -f /data/cmd.sql
fi

MEM_OPT="-Xmx128M"
java "$MEM_OPT" -cp BOOT-INF/classes:BOOT-INF/lib/* cn.har01d.alist_tvbox.AListApplication "$@"
