#!/usr/bin/env bash
set -e

LOCAL_VERSION1="0.0.0"
LOCAL_VERSION2="0.0.0"
VERSION1=$(curl -s https://api.github.com/repos/power721/alist-tvbox/releases/latest | grep '"tag_name"' | cut -d '"' -f 4)
VERSION2=$(curl -s https://api.github.com/repos/power721/alist/releases/latest | grep '"tag_name"' | cut -d '"' -f 4)
USER=$(id -nu)
GROUP=$(id -gn)
APPNAME=atv

if [ -f /opt/${APPNAME}/data/app_version ]; then
  LOCAL_VERSION1=$(head -n 1 </opt/${APPNAME}/data/app_version)
fi

if [ -f /opt/${APPNAME}/alist/data/version ]; then
  LOCAL_VERSION2=$(head -n 1 </opt/${APPNAME}/alist/data/version)
fi

if [ "$LOCAL_VERSION1" = "$VERSION1" ] && [ "$LOCAL_VERSION2" = "$VERSION2" ] ; then
    echo "Already latest version"
    echo "AList: $VERSION2"
    echo "AList TvBox: $VERSION1"
    exit 0
fi

sudo mkdir -p /opt/${APPNAME}/alist/{data,log}
sudo mkdir -p /opt/${APPNAME}/{config,scripts,index,log}
sudo mkdir -p /opt/${APPNAME}/data/{atv,backup}
sudo mkdir -p /opt/${APPNAME}/www/{cat,pg,zx,tvbox,files}

cat <<EOF >/tmp/${APPNAME}.yaml
spring:
  datasource:
    url: jdbc:h2:file:/opt/${APPNAME}/data/data
EOF

conf=/opt/${APPNAME}/config/application-production.yaml
[ -f $conf ] || sudo mv /tmp/${APPNAME}.yaml $conf

# TODO: download scripts
# TODO: init alist

mkdir -p /tmp/${APPNAME}

[ "$LOCAL_VERSION1" != "$VERSION1" ] && \
echo "Upgrade atv from $LOCAL_VERSION1 to $VERSION1" && \
wget https://github.com/power721/alist-tvbox/releases/download/$VERSION1/atv.tar.gz -O /tmp/${APPNAME}/atv.tgz && \
tar xf /tmp/${APPNAME}/atv.tgz -C /tmp/${APPNAME}/

[ "$LOCAL_VERSION2" != "$VERSION2" ] && \
echo "Upgrade alist from $LOCAL_VERSION2 to $VERSION2" && \
wget https://github.com/power721/alist/releases/download/$VERSION2/alist-linux-musl-amd64.tar.gz -O /tmp/${APPNAME}/alist.tgz && \
tar xf /tmp/${APPNAME}/alist.tgz -C /tmp/${APPNAME}/

cat <<EOF > /tmp/${APPNAME}.service
[Unit]
Description=${APPNAME} API
After=syslog.target

[Service]
User=${USER}
Group=${GROUP}
WorkingDirectory=/opt/${APPNAME}
ExecStart=/opt/${APPNAME}/${APPNAME} --spring.profiles.active=standalone,production
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

sudo mv /tmp/${APPNAME}.service /etc/systemd/system/${APPNAME}.service
sudo systemctl daemon-reload
sudo systemctl stop ${APPNAME}.service

[ "$LOCAL_VERSION1" != "$VERSION1" ] && sudo mv /tmp/${APPNAME}/atv /opt/${APPNAME}/${APPNAME}
[ "$LOCAL_VERSION2" != "$VERSION2" ] && \
sudo mv /tmp/${APPNAME}/alist /opt/${APPNAME}/alist/alist && \
cd /opt/${APPNAME}/alist/ && \
/opt/${APPNAME}/alist/alist admin
sudo chmod +x /opt/${APPNAME}/${APPNAME} /opt/${APPNAME}/alist/alist
sudo echo $VERSION1 > /opt/${APPNAME}/data/app_version
sudo echo $VERSION2 > /opt/${APPNAME}/alist/data/version
sudo chown -R ${USER}:${GROUP} /opt/${APPNAME}

sudo systemctl enable ${APPNAME}.service
sudo systemctl start ${APPNAME}.service
sleep 3
sudo systemctl status ${APPNAME}.service
