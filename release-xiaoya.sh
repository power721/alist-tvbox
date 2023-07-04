if [ $# -lt 1 ]; then
  echo "$0 <VERSION>"
  exit 1
fi

VERSION=$1

rm -rf src/main/resources/static/assets && \
cd web-ui && \
npm run build || exit 1
cd .. && \
git add src/main/resources/static

mvn clean package || exit 1

cd target && java -Djarmode=layertools -jar alist-tvbox-1.0.jar extract && cd ..

mv src/main/resources/data.sql data
docker image prune -f
docker pull haroldli/alist-base:latest
date +%j.%H%M > data/version
docker build -f Dockerfile-xiaoya --tag=haroldli/xiaoya-tvbox:latest .
scp data/version ubuntu@1.117.140.221:/var/www/alist/app_version
cp data/data.sql src/main/resources/
docker rm -f xiaoya-tvbox
docker run -d -p 18080:8080 --rm -v /etc/xiaoya:/data --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest

docker logs xiaoya-tvbox

docker rm -f xiaoya-tvbox

docker image tag haroldli/xiaoya-tvbox:latest haroldli/xiaoya-tvbox:$VERSION

docker images | grep haroldli/xiaoya-tvbox

docker push haroldli/xiaoya-tvbox:latest
docker push haroldli/xiaoya-tvbox:$VERSION
