LOCAL="0.0"
if [ -f /data/atv/movie_version ]; then
  LOCAL=$(head -n 1 </data/atv/movie_version)
fi

echo "local movie data version: ${LOCAL}, remote version: ${REMOTE}"
if [ "$LOCAL" = "$1" ]; then
  exit
fi

echo "download diff.zip" && \
wget http://har01d.org/diff.zip -O diff.zip && \
unzip -q -o diff.zip -d /data/atv/ && \
cat /data/atv/movie_version && \
rm -f /tmp/diff.zip
