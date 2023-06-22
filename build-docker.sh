rm -rf src/main/resources/static/assets && \
cd web-ui && \
npm run build || exit 1
cd .. && \
git add src/main/resources/static

cp src/main/resources/application.yaml application-backup.yaml
sed -i '/- name: 本地/,+2d' src/main/resources/application.yaml
sed -i '/sites:/r add.txt' src/main/resources/application.yaml
mvn clean package || exit 1

mv application-backup.yaml src/main/resources/application.yaml

date +%j.%H%M > data/version
docker build --tag=alist-tvbox:latest .

docker images | grep alist-tvbox

docker run -d -p 18080:8080 --rm --name=alist-tvbox alist-tvbox

docker image save alist-tvbox:latest > ~/alist-tvbox.tar

sleep 20

docker logs alist-tvbox

curl http://localhost:18080/vod

echo ""
docker rm -f alist-tvbox
