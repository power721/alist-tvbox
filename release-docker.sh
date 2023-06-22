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

cp src/main/resources/application.yaml application-backup.yaml
sed -i '/- name: 本地/,+2d' src/main/resources/application.yaml
sed -i '/sites:/r add.txt' src/main/resources/application.yaml
sed -i '/- name: 本地/,+3d' src/main/resources/application.yaml
mvn clean package || exit 1

mv application-backup.yaml src/main/resources/application.yaml

date +%j.%H%M > data/version
docker build --tag=haroldli/alist-tvbox:latest .
docker rm -f alist-tvbox
docker run -d -p 18080:8080 --rm --name=alist-tvbox haroldli/alist-tvbox:latest

docker logs alist-tvbox

docker rm -f alist-tvbox

docker image tag haroldli/alist-tvbox:latest haroldli/alist-tvbox:$VERSION

docker images | grep haroldli/alist-tvbox

docker push haroldli/alist-tvbox:latest
docker push haroldli/alist-tvbox:$VERSION
