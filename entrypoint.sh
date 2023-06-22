#!/bin/sh

/updateall

ln -sf /data/config
mv /var/lib/nginx.conf /etc/nginx/http.d/default.conf

/bin/busybox-extras httpd -p 81 -h /www
/usr/sbin/nginx

if [[ -f /data/pikpak.txt ]] && [[ -s /data/pikpak.txt ]]; then
  /pikpak
  echo $(date) "User's own PikPak account has been updated into database successfully"
fi

version=$(head -n1 /docker.version)
sqlite3 /opt/alist/data/data.db <<EOF
INSERT INTO x_storages VALUES(20000,'/©️ $version',0,'AList V3',30,'work','{"root_folder_path":"/安装，配置，修复 xiaoya docker 指南/打赏码，谢谢你的支持.jpg","url":"http://alist.xiaoya.pro","password":"","access_token":""}','','2022-11-12 13:05:12.467024193+00:00',0,'','','',0,'302_redirect','');
EOF

if [[ -f /data/proxy.txt ]] && [[ -s /data/proxy.txt ]]; then
  proxy_url=$(head -n1 /data/proxy.txt)
  export HTTP_PROXY=$proxy_url
  export HTTPS_PROXY=$proxy_url
  export no_proxy=*.aliyundrive.com
fi

echo "download data.zip" && \
wget http://d.har01d.cn/data.zip -O data.zip && \
unzip -q -o data.zip && \
cat data/movie_version && \
rm -f data.zip

if [ -f /data/cmd.sql ]; then
  cat /data/cmd.sql >> data/data.sql
  rm -f /data/cmd.sql
fi

unzip -q -o app.jar && rm -f app.jar
java -cp BOOT-INF/classes:BOOT-INF/lib/* cn.har01d.alist_tvbox.AListApplication --spring.profiles.active=production,xiaoya
