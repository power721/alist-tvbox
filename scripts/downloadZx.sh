if ps aux | grep -v grep | grep -q wget ; then
  exit 1
fi

LOCAL2="0.0"
if [ -f /data/zx_base_version.txt ]; then
  LOCAL2=$(head -n 1 </data/zx_base_version.txt)
fi

REMOTE2=$(curl -s http://har01d.org/zx.base.version)

echo "locale zx bas: ${LOCAL2}, remote zx base: ${REMOTE2}"
if [ "$LOCAL2" != "${REMOTE2}" ]; then
  echo "download zx base ${REMOTE2}" && \
  wget http://har01d.org/zx.base.zip -O /data/zx.base.zip && \
  echo "save zx base version" && \
  echo -n ${REMOTE2} > /data/zx_base_version.txt
fi

LOCAL="0.0"
if [ -f /data/zx_version.txt ]; then
  LOCAL=$(head -n 1 </data/zx_version.txt)
else
  cp /zx.zip /data/
fi

REMOTE=$(curl -s http://har01d.org/zx.version)

echo "local zx diff: ${LOCAL}, remote zx diff: ${REMOTE}"
if [ "$LOCAL" != "${REMOTE}" ]; then
  echo "download zx diff ${REMOTE}" && \
  wget http://har01d.org/zx.zip -O /data/zx.zip && \
  echo "save zx diff version" && \
  echo -n ${REMOTE} > /data/zx_version.txt
fi

ls -l /data/zx.base.zip /data/zx.zip

echo "sync zx files"
rm -rf /www/zx/* && \
echo "unzip zx.base.zip" && \
unzip -q -o /data/zx.base.zip -d /www/zx && \
echo "unzip zx.zip" && \
unzip -q -o /data/zx.zip -d /www/zx && \
echo "sync custom files" && \
[ -d /data/zx ] && \
cp -r /data/zx/* /www/zx/ && \
echo "update zx completed"
