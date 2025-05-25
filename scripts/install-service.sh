#!/usr/bin/env bash

USERNAME=atv
APPNAME=atv

sudo adduser --system --group --no-create-home ${USERNAME}
sudo mkdir -p /opt/${APPNAME}/config
sudo mkdir -p /opt/${APPNAME}/data
sudo mkdir -p /opt/${APPNAME}/log

sudo mkdir -p /opt/alist/data
sudo mkdir -p /opt/alist/log

sudo touch /opt/${APPNAME}/config/application-production.yaml

wget https://github.com/power721/alist-tvbox/releases/download/v1.0.1/alist-tvbox-1.0.jar -O /opt/${APPNAME}/${APPNAME}

sudo chown -R ${USERNAME}:${USERNAME} /opt/${APPNAME}/

cat <<EOT > /tmp/${APPNAME}.service
[Unit]
Description=${APPNAME} API
After=syslog.target

[Service]
User=${USERNAME}
WorkingDirectory=/opt/${APPNAME}
ExecStart=java -jar /opt/${APPNAME}/${APPNAME} --spring.profiles.active=production
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOT

[[ -f /etc/systemd/system/${APPNAME}.service ]] || sudo cp /tmp/${APPNAME}.service /etc/systemd/system/
sudo systemctl stop ${APPNAME}.service
sudo cp ${APPNAME}.jar /opt/${APPNAME}/${APPNAME}
sudo chown ${USERNAME}:${USERNAME} /opt/${APPNAME}/${APPNAME}
sudo chmod a+x /opt/${APPNAME}/${APPNAME}

sudo systemctl enable ${APPNAME}.service
sudo systemctl restart ${APPNAME}.service
sleep 3
sudo systemctl status ${APPNAME}.service
