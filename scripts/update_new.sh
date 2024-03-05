BASE_DIR=./data
PORT1=4567
PORT2=5344
YES=false
UPDATE=false
MOUNT=""

while getopts ":d:p:P:v:yu" arg; do
    case "${arg}" in
        d)
            BASE_DIR=${OPTARG}
            ;;
        p)
            PORT1=${OPTARG}
            ;;
        P)
            PORT2=${OPTARG}
            ;;
        v)
            MOUNT="${MOUNT} -v ${OPTARG}"
            ;;
        y)
            YES=true
            ;;
        u)
            UPDATE=true
            ;;
        *)
            ;;
    esac
done

shift $((OPTIND-1))

if [ $# -gt 0 ]; then
	BASE_DIR=$1
fi

if [ $# -gt 1 ]; then
	PORT1=$2
fi

if docker ps | awk '{print $NF}' | grep -q xiaoya-tvbox; then
  echo -e "\e[33m集成版Docker容器运行中。\e[0m"
  if [ "$YES" = "true" ]; then
    echo -e "\e[33m停止集成版Docker容器\e[0m"
    docker rm -f xiaoya-tvbox 2>/dev/null
  else
    read -r -p "是否停止集成版Docker容器？[Y/N] " yn
    case $yn in
        [Yy]* ) docker rm -f xiaoya-tvbox 2>/dev/null;;
        [Nn]* ) exit 0;;
    esac
  fi
fi

echo -e "\e[36m端口映射：\e[0m $PORT1:4567  $PORT2:5244"

echo -e "\e[33m默认端口变更为4567\e[0m"

docker container prune -f --filter "label=MAINTAINER=Har01d"
docker image prune -f --filter "label=MAINTAINER=Har01d"
docker volume prune -f --filter "label=MAINTAINER=Har01d"

platform="linux/amd64"
TAG="latest"
ARCH=$(uname -m)
if [ "$ARCH" = "armv7l" ]; then
  platform="linux/arm/v7"
  TAG="arm-v7"
elif [ "$ARCH" = "aarch64" ]; then
    platform="linux/arm64"
fi

IMAGE_ID=$(docker images -q haroldli/xiaoya-tvbox:${TAG})
echo -e "\e[32m下载最新Docker镜像，平台：${platform}\e[0m"
for i in 1 2 3 4 5
do
   docker pull --platform ${platform} haroldli/alist-tvbox:${TAG} && break
done

NEW_IMAGE=$(docker images -q haroldli/xiaoya-tvbox:${TAG})
if [ "$UPDATE" = "true" ] && [ "$IMAGE_ID" = "$NEW_IMAGE" ]; then
  echo -e "\e[33m镜像没有更新\e[0m"
  exit
fi

echo -e "\e[33m重启应用\e[0m"
docker rm -f alist-tvbox && \
docker run -d -p $PORT1:4567 -p $PORT2:5244 -e ALIST_PORT=$PORT2 --restart=always -v "$BASE_DIR":/data ${MOUNT} --name=alist-tvbox haroldli/alist-tvbox:${TAG}

echo -e "\n\e[32m请使用以下命令查看日志输出：\e[0m"
echo -e "    docker logs -f alist-tvbox\n"

IP=$(ip a | grep -F '192.168.' | awk '{print $2}' | awk -F/ '{print $1}' | head -1)
if [ -n "$IP" ]; then
  echo ""
  echo -e "\e[32m请用以下地址访问：\e[0m"
  echo -e "    \e[32m管理界面\e[0m： http://$IP:$PORT1/"
  echo -e "    \e[32mAList\e[0m： http://$IP:$PORT2/"
else
  echo -e "\e[32m云服务器请用公网IP访问\e[0m"
fi
echo ""

echo -e "\e[33m默认端口变更为4567\e[0m"
