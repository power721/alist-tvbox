set -e

MOUNT=/etc/xiaoya
PORT1=5678
PORT2=5244

if [ $# -eq 1 ]; then
  MOUNT=$1
  echo "will mount the data path: $MOUNT"
fi

if [ $# -gt 1 ]; then
	PORT1=$2
fi

if [ $# -gt 2 ]; then
	PORT2=$3
fi

rm -rf src/main/resources/static/assets && \
cd web-ui && \
npm run build || exit 1
cd .. && \
git add src/main/resources/static

mvn clean package || exit 1

cd target && java -Djarmode=layertools -jar alist-tvbox-1.0.jar extract && cd ..

echo -e "\e[36m使用配置目录：\e[0m $MOUNT"
echo -e "\e[36m端口映射：\e[0m $PORT1:8080  $PORT2:80"

docker pull xiaoyaliu/alist
docker pull haroldli/alist-base
docker image prune -f
date +%j.%H%M > data/version
docker build -f Dockerfile-xiaoya --tag=haroldli/xiaoya-tvbox:latest . || exit 1
docker rm -f xiaoya-tvbox xiaoya alist-tvbox 2>/dev/null
docker run -d -p $PORT1:8080 -p $PORT2:80 -e ALIST_PORT=$PORT2 -v "$MOUNT":/data --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest

sleep 1

IP=$(ip a | grep -F '192.168.' | awk '{print $2}' | awk -F/ '{print $1}' | head -1)
if [ -n "$IP" ]; then
  echo -e "\e[32m请用以下地址访问：\e[0m"
  echo -e "    \e[32m管理界面\e[0m： http://$IP:$PORT1/"
  echo -e "    \e[32m小雅AList\e[0m： http://$IP:$PORT2/"
else
  echo -e "\e[32m云服务器请用公网IP访问\e[0m"
fi
echo ""

docker logs -f xiaoya-tvbox
