#!/usr/bin/env bash
set -e

LOCAL_VERSION=""
LOCAL_APP_VERSION=""
VERSION=$(curl -s https://api.github.com/repos/power721/alist/releases/latest | grep '"tag_name"' | cut -d '"' -f 4)
APP_VERSION=$(curl -s https://api.github.com/repos/power721/alist-tvbox/releases/latest | grep '"tag_name"' | cut -d '"' -f 4)
USERNAME=$USER
APPNAME=atv

if [ -f /opt/${APPNAME}/data/app_version ]; then
  LOCAL_APP_VERSION=$(head -n 1 </opt/${APPNAME}/data/app_version)
fi

if [ -f /opt/${APPNAME}/alist/data/version ]; then
  LOCAL_VERSION=$(head -n 1 </opt/${APPNAME}/alist/data/version)
fi

if [ "$LOCAL_APP_VERSION" = "$APP_VERSION" ] && [ "$LOCAL_VERSION" = "$VERSION" ] ; then
    echo "Already latest version"
    echo "AList: $VERSION"
    echo "AList TvBox: $APP_VERSION"
    exit 0
fi

sudo mkdir -p /opt/${APPNAME}/alist/data
sudo mkdir -p /opt/${APPNAME}/alist/log
sudo mkdir -p /opt/${APPNAME}/config
sudo mkdir -p /opt/${APPNAME}/data/backup
sudo mkdir -p /opt/${APPNAME}/data/atv
sudo mkdir -p /opt/${APPNAME}/index
sudo mkdir -p /opt/${APPNAME}/log
sudo mkdir -p /opt/${APPNAME}/scripts
sudo mkdir -p /opt/${APPNAME}/www/cat
sudo mkdir -p /opt/${APPNAME}/www/pg
sudo mkdir -p /opt/${APPNAME}/www/zx
sudo mkdir -p /opt/${APPNAME}/www/tvbox
sudo mkdir -p /opt/${APPNAME}/www/files

cat <<EOF >/tmp/${APPNAME}.yaml
spring:
  datasource:
    url: jdbc:h2:file:/opt/${APPNAME}/data/data
EOF

conf=/opt/${APPNAME}/config/application-production.yaml
[ -f $conf ] || sudo mv /tmp/${APPNAME}.yaml $conf

# TODO: download scripts
# TODO: download alist
# TODO: init alist

[ "$LOCAL_APP_VERSION" = "$APP_VERSION" ] || wget https://github.com/power721/alist-tvbox/releases/download/$APP_VERSION/atv -O /tmp/${APPNAME}
[ "$LOCAL_VERSION" = "$VERSION" ] || wget https://github.com/power721/alist/releases/download/$VERSION/alist-linux-musl-amd64.tar.gz -O /tmp/alist.tgz && tar xf /tmp/alist.tgz -C /tmp

cat <<EOF > /tmp/${APPNAME}.service
[Unit]
Description=${APPNAME} API
After=syslog.target

[Service]
User=${USERNAME}
WorkingDirectory=/opt/${APPNAME}
ExecStart=/opt/${APPNAME}/${APPNAME} --spring.profiles.active=standalone,production
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

sudo mv /tmp/${APPNAME}.service /etc/systemd/system/${APPNAME}.service
sudo systemctl daemon-reload
sudo systemctl stop ${APPNAME}.service
[ "$LOCAL_APP_VERSION" = "$APP_VERSION" ] || sudo mv /tmp/${APPNAME} /opt/${APPNAME}/${APPNAME}
[ "$LOCAL_VERSION" = "$VERSION" ] || mv /tmp/alist /opt/${APPNAME}/alist/
sudo chmod +x /opt/${APPNAME}/${APPNAME}
sudo echo $APP_VERSION > /opt/${APPNAME}/data/app_version
sudo echo $VERSION > /opt/${APPNAME}/alist/data/version
sudo chown -R ${USERNAME}:${USERNAME} /opt/${APPNAME}

sudo systemctl enable ${APPNAME}.service
sudo systemctl start ${APPNAME}.service
sleep 3
sudo systemctl status ${APPNAME}.service
