if ps aux | grep -v grep | grep -q wget ; then
  exit 1
fi

LOCAL="0.0"
if [ -f /data/heart_version.txt ]; then
  LOCAL=$(head -n 1 </data/heart_version.txt)
else
  cp /heart.zip /data/
fi

REMOTE=$(curl -s https://gitlab.com/power0721/pg/-/raw/main/version1.txt)

echo "local PG: ${LOCAL}, remote PG: ${REMOTE}"
if [ "$LOCAL" = "${REMOTE}" ]; then
  echo "sync files"
  rm -rf /www/heart/* && unzip -q -o /data/heart.zip -d /www/heart && [ -d /data/heart ] && cp -r /data/heart/* /www/heart/
  exit 2
fi

echo "download ${REMOTE}" && \
wget https://gitlab.com/power0721/pg/-/raw/main/heart.zip -O /data/heart.zip && \
echo "unzip file" && \
rm -rf /www/heart/* && unzip -q -o /data/heart.zip -d /www/heart && \
echo "save version" && \
echo -n ${REMOTE} > /data/heart_version.txt && \
echo "sync files" && \
[ -d /data/heart ] && \
cp -r /data/heart/* /www/heart/
