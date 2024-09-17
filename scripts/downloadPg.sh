LOCAL="0.0"
if [ -f /data/pg_version.txt ]; then
  LOCAL=$(head -n 1 </data/pg_version.txt)
else
  cp /pg.zip /data/
fi

REMOTE=$(curl -s https://raw.githubusercontent.com/power721/pg/refs/heads/main/version.txt)

echo "local PG: ${LOCAL}, remote PG: ${REMOTE}"
if [ "$LOCAL" = "${REMOTE}" ]; then
  echo "sync files"
  rm -rf /www/pg/* && unzip -q -o /data/pg.zip -d /www/pg && cp -r /data/pg/* /www/pg/
  exit
fi

echo "download ${REMOTE}" && \
wget https://github.com/power721/pg/raw/refs/heads/main/pg.zip -O /data/pg.zip && \
echo "sync files" && \
rm -rf /www/pg/* && unzip -q -o /data/pg.zip -d /www/pg && cp -r /data/pg/* /www/pg/ && \
echo "save version" && \
echo -n ${REMOTE} > /data/pg_version.txt
