#!/usr/bin/env bash

USERNAME=atv
APPNAME=atv

sudo adduser --system --group --no-create-home ${USERNAME}

sudo mkdir -p /opt/${APPNAME}/alist/data
sudo mkdir -p /opt/${APPNAME}/alist/log
sudo mkdir -p /opt/${APPNAME}/config
sudo mkdir -p /opt/${APPNAME}/data/backup
sudo mkdir -p /opt/${APPNAME}/data/temp
sudo mkdir -p /opt/${APPNAME}/index
sudo mkdir -p /opt/${APPNAME}/log

cat <<EOF >/tmp/${APPNAME}.yaml
spring:
  datasource:
    url: jdbc:h2:file:/opt/atv/data/data
EOF

sudo mv /tmp/${APPNAME}.yaml /opt/${APPNAME}/config/application-production.yaml

sudo wget https://github.com/power721/alist-tvbox/releases/download/v1.0.1/alist-tvbox-1.0.jar -O /opt/${APPNAME}/alist-tvbox.jar

sudo chown -R ${USERNAME}:${USERNAME} /opt/${APPNAME}/

cat <<EOF > /tmp/${APPNAME}.service
[Unit]
Description=${APPNAME} API
After=syslog.target

[Service]
User=${USERNAME}
WorkingDirectory=/opt/${APPNAME}
ExecStart=java -jar /opt/${APPNAME}/alist-tvbox.jar --spring.profiles.active=production
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

[[ -f /etc/systemd/system/${APPNAME}.service ]] || sudo cp /tmp/${APPNAME}.service /etc/systemd/system/
sudo systemctl stop ${APPNAME}.service
sudo chown -R ${USERNAME}:${USERNAME} /opt/${APPNAME}

sudo systemctl enable ${APPNAME}.service
sudo systemctl restart ${APPNAME}.service
sleep 3
sudo systemctl status ${APPNAME}.service
