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
mvn clean package || exit 1

cd target && java -Djarmode=layertools -jar alist-tvbox-1.0.jar extract && cd ..

docker image prune -f
docker pull haroldli/alist-base:hostmode
echo $((($(date +%Y) - 2023) * 366 + $(date +%j | sed 's/^0*//'))).$(date +%H%M) > data/version
docker build -f Dockerfile-host --tag=haroldli/xiaoya-tvbox:hostmode . || exit 1
docker rm -f xiaoya-tvbox xiaoya alist-tvbox 2>/dev/null
docker run -d --network host -e INSTALL=hostmode -v "$MOUNT":/data --name=xiaoya-tvbox haroldli/xiaoya-tvbox:hostmode

sleep 1

docker logs -f xiaoya-tvbox
