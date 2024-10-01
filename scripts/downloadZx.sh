if ps aux | grep -v grep | grep -q wget ; then
  exit 1
fi

LOCAL="0.0"
if [ -f /data/zx_version.txt ]; then
  LOCAL=$(head -n 1 </data/zx_version.txt)
else
  cp /zx.zip /data/
fi

REMOTE=$(curl -s http://104.160.46.225/zx.version)

echo "local zx: ${LOCAL}, remote zx: ${REMOTE}"
if [ "$LOCAL" = "${REMOTE}" ]; then
  echo "sync files"
  rm -rf /www/zx/* && unzip -q -o /data/zx.zip -d /www/zx && [ -d /data/zx ] && cp -r /data/zx/* /www/zx/
  exit 2
fi

echo "download ${REMOTE}" && \
wget http://104.160.46.225/zx.zip -O /data/zx.zip && \
echo "unzip file" && \
rm -rf /www/zx/* && unzip -q -o /data/zx.zip -d /www/zx && \
echo "save version" && \
echo -n ${REMOTE} > /data/zx_version.txt && \
echo "sync files" && \
[ -d /data/zx ] && \
cp -r /data/zx/* /www/zx/
