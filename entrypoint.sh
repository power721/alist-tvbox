#!/bin/sh

chmod a+x /init.sh /index.sh

/init.sh

/bin/busybox-extras httpd -p 81 -h /www
/usr/sbin/nginx

version=$(head -n1 /docker.version)
sqlite3 /opt/alist/data/data.db <<EOF
INSERT INTO x_storages VALUES(20000,'/©️ $version',0,'AList V3',30,'work','{"root_folder_path":"/安装，配置，修复 xiaoya docker 指南/打赏码，谢谢你的支持.jpg","url":"http://alist.xiaoya.pro","password":"","access_token":""}','','2022-11-12 13:05:12.467024193+00:00',0,'','','',0,'302_redirect','');
EOF

if [ -f /data/proxy.txt ]; then
  proxy_url=$(head -n1 /data/proxy.txt)
  export HTTP_PROXY=$proxy_url
  export HTTPS_PROXY=$proxy_url
  export no_proxy="*.aliyundrive.com"
fi

echo "download data.zip" && \
wget http://d.har01d.cn/data.zip -O data.zip && \
unzip -q -o data.zip && \
cat data/movie_version && \
rm -f data.zip

ln -sf /data/config .

if [ -f /data/cmd.sql ]; then
  echo "add cmd.sql"
  wc /data/cmd.sql
  cat /data/cmd.sql >> data/data.sql
  rm -f /data/cmd.sql
fi

java -cp BOOT-INF/classes:BOOT-INF/lib/* cn.har01d.alist_tvbox.AListApplication --spring.profiles.active=production,xiaoya
