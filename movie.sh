LOCAL="0.0"
if [ -f /data/atv/movie_version ]; then
  LOCAL=$(head -n 1 </data/atv/movie_version)
fi

echo "local movie data version: ${LOCAL}, remote version: ${REMOTE}"
if [ "$LOCAL" = "$1" ]; then
  exit
fi

echo "download data.zip" && \
wget http://d.har01d.cn/data.zip -O data.zip && \
unzip -q -o data.zip -d /tmp && \
cp /tmp/data/movie_version /data/atv/ && \
cp /tmp/data/data.sql /data/atv/ && \
cat /data/atv/movie_version && \
rm -f /tmp/data.zip
