if ps aux | grep -v grep | grep -q wget ; then
  exit 1
fi

LOCAL="0.0"
if [ -f /data/pg_version.txt ]; then
  LOCAL=$(head -n 1 </data/pg_version.txt)
else
  cp /pg.zip /data/
fi

REMOTE=$(curl -s http://har01d.org/pg.version)

echo "local PG: ${LOCAL}, remote PG: ${REMOTE}"
if [ "$LOCAL" = "${REMOTE}" ]; then
  echo "sync files"
  rm -rf /www/pg/* && unzip -q -o /data/pg.zip -d /www/pg && [ -d /data/pg ] && cp -r /data/pg/* /www/pg/
  exit 2
fi

echo "download ${REMOTE}" && \
wget http://har01d.org/pg.zip -O /data/pg.zip && \
echo "unzip file" && \
rm -rf /www/pg/* && unzip -q -o /data/pg.zip -d /www/pg && \
echo "save version" && \
echo -n ${REMOTE} > /data/pg_version.txt && \
echo "sync files" && \
[ -d /data/pg ] && \
cp -r /data/pg/* /www/pg/
