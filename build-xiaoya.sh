set -e

MOUNT=/etc/xiaoya

if [ $# -eq 1 ]; then
  MOUNT=$1
  echo "will mount the data path: $MOUNT"
fi

rm -rf src/main/resources/static/assets && \
cd web-ui && \
npm run build || exit 1
cd .. && \
git add src/main/resources/static

mvn clean package || exit 1

docker image prune -f
docker pull xiaoyaliu/alist:latest
date +%j.%H%M > data/version
docker build -f Dockerfile-xiaoya --tag=haroldli/xiaoya-tvbox:latest . || exit 1
docker rm -f xiaoya-tvbox xiaoya alist-tvbox 2>/dev/null
docker run -d -p 5678:8080 -p 5244:80 -v "$MOUNT":/data --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest

sleep 1

docker logs -f xiaoya-tvbox
