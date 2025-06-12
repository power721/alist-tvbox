#!/usr/bin/env bash
set -e

VERSION1=$(curl -s http://d.har01d.cn/app.version.txt)
VERSION2=$(curl -s http://d.har01d.cn/alist.version.txt)

LOCAL_VERSION1="0.0.0"
LOCAL_VERSION2="0.0.0"

GROUP=$(id -rgn $USER)
APP=atv

if [ -f /opt/${APP}/data/app_version ]; then
  LOCAL_VERSION1=$(head -n 1 </opt/${APP}/data/app_version)
fi

if [ -f /opt/${APP}/alist/data/version ]; then
  LOCAL_VERSION2=$(head -n 1 </opt/${APP}/alist/data/version)
fi

if [ "$LOCAL_VERSION1" = "$VERSION1" ] && [ "$LOCAL_VERSION2" = "$VERSION2" ] ; then
    echo "Already latest version"
    echo "Power AList: $VERSION2"
    echo "AList TvBox: $VERSION1"
    exit 0
fi

echo "User: $USER:$GROUP"

sudo mkdir -p /opt/${APP}/alist/{data,log}
sudo mkdir -p /opt/${APP}/{config,scripts,index,log}
sudo mkdir -p /opt/${APP}/data/{atv,backup}
sudo mkdir -p /opt/${APP}/www/{cat,pg,zx,tvbox,files}
sudo chown -R ${USER}:${GROUP} /opt/${APP}

cat <<EOF >${APP}.yaml
spring:
  datasource:
    url: jdbc:h2:file:/opt/${APP}/data/data
EOF

conf=/opt/${APP}/config/application-production.yaml
[ -f $conf ] || sudo mv ${APP}.yaml $conf

# TODO: download scripts

[ "$LOCAL_VERSION1" != "$VERSION1" ] && \
echo "Upgrade AList TvBox from $LOCAL_VERSION1 to $VERSION1" && \
wget https://github.com/power721/alist-tvbox/releases/download/$VERSION1/atv.tar.gz -O atv.tgz && \
tar xf atv.tgz && rm -f atv.tgz

[ "$LOCAL_VERSION2" != "$VERSION2" ] && \
echo "Upgrade Power AList from $LOCAL_VERSION2 to $VERSION2" && \
wget https://github.com/power721/alist/releases/download/$VERSION2/alist-linux-musl-amd64.tar.gz -O alist.tgz && \
tar xf alist.tgz && rm -f alist.tgz

cat <<EOF > ${APP}.service
[Unit]
Description=${APP} API
After=syslog.target

[Service]
User=${USER}
Group=${GROUP}
WorkingDirectory=/opt/${APP}
ExecStart=/opt/${APP}/${APP} --spring.profiles.active=standalone,production
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

sudo mv ${APP}.service /etc/systemd/system/${APP}.service
sudo systemctl daemon-reload
sudo systemctl stop ${APP}.service

[ "$LOCAL_VERSION1" != "$VERSION1" ] && sudo mv atv /opt/${APP}/${APP} && \
sudo chown ${USER}:${GROUP} /opt/${APP}/${APP} && \
chmod +x /opt/${APP}/${APP} && \
echo $VERSION1 > /opt/${APP}/data/app_version

[ "$LOCAL_VERSION2" != "$VERSION2" ] && \
sudo mv alist /opt/${APP}/alist/alist && \
cd /opt/${APP}/alist/ && \
sudo chown -R ${USER}:${GROUP} /opt/${APP}/alist  && \
chmod +x alist && \
./alist admin && \
echo $VERSION2 > /opt/${APP}/alist/data/version

sudo systemctl enable ${APP}.service
sudo systemctl start ${APP}.service
sleep 3
sudo systemctl status ${APP}.service
