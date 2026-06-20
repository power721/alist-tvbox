#!/bin/sh

if [ -r /data/env ]; then
    echo "Reading env file"
    cp /data/env /etc/profile.d/custom_env.sh
    chmod +x /etc/profile.d/custom_env.sh
fi

if [ -r /data/proxy.txt ]; then
  proxy_url=$(head -n1 /data/proxy.txt)
  export HTTP_PROXY=$proxy_url
  export HTTPS_PROXY=$proxy_url
  export no_proxy="*.aliyundrive.com"
fi

chmod a+x /*.sh

if [ ! -d /data/log ]; then
  mkdir -p /data/log
fi
ln -sf /data/log /opt/atv/log

/init.sh 2>&1 | tee /opt/atv/log/init.log 2>&1

# Exit code 85 = startup JSON restore completed; relaunch for a clean boot on restored data.
while true; do
  /jre/bin/java "$MEM_OPT" -cp BOOT-INF/classes:BOOT-INF/lib/* cn.har01d.alist_tvbox.AListApplication "$@"
  code=$?
  if [ "$code" = "85" ]; then
    echo "=== restart after json restore ==="
    continue
  fi
  exit $code
done
