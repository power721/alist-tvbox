BASE_DIR=/etc/xiaoya
PORT1=4567
PORT2=5344
TAG="latest"

if [ "$1" = "-t" ]; then
  TAG="$2"
  shift 2
fi

if [ $# -gt 0 ]; then
	BASE_DIR=$1
fi

if [ $# -gt 1 ]; then
	PORT1=$2
fi

if [ $# -gt 2 ]; then
	PORT2=$3
fi

if docker ps | awk '{print $NF}' | grep -q alist-tvbox; then
  echo -e "\e[33m独立版Docker容器运行中。\e[0m"
  read -r -p "是否停止独立版Docker容器？[Y/N] " yn
  case $yn in
      [Yy]* ) docker rm -f alist-tvbox 2>/dev/null;;
      [Nn]* ) exit 0;;
  esac
fi

echo -e "\e[36m使用配置目录：\e[0m $BASE_DIR"
echo -e "\e[36m端口映射：\e[0m $PORT1:4567  $PORT2:80"

echo -e "\e[33m默认端口变更为4567\e[0m"

mkdir -p $BASE_DIR

if ! grep "access.mypikpak.com" /etc/hosts >/dev/null
then
	echo -e "127.0.0.1\taccess.mypikpak.com" >> /etc/hosts
fi

docker image prune -f

platform="linux/amd64"
ARCH=$(uname -m)
if [ "$ARCH" = "armv7l" ]; then
  echo "不支持的平台"
  exit 1
elif [ "$ARCH" = "aarch64" ]; then
    platform="linux/arm64"
fi

echo -e "\e[32m下载最新Docker镜像，平台：${platform}, image tag: ${TAG}\e[0m"
for i in 1 2 3 4 5
do
   docker pull --platform ${platform} haroldli/xiaoya-tvbox:${TAG} && break
done

echo -e "\e[33m重启应用\e[0m"
docker rm -f xiaoya-tvbox 2>/dev/null && \
docker run -d -p $PORT1:4567 -p $PORT2:80 -e ALIST_PORT=$PORT2 -v "$BASE_DIR":/data --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:${TAG}

echo -e "\n\e[32m请使用以下命令查看日志输出：\e[0m"
echo -e "    docker logs -f xiaoya-tvbox\n"

IP=$(ip a | grep -F '192.168.' | awk '{print $2}' | awk -F/ '{print $1}' | head -1)
if [ -n "$IP" ]; then
  echo ""
  echo -e "\e[32m请用以下地址访问：\e[0m"
  echo -e "    \e[32m管理界面\e[0m： http://$IP:$PORT1/"
  echo -e "    \e[32m小雅AList\e[0m： http://$IP:$PORT2/"
else
  IP=$(ip a | grep -F '10.' | awk '{print $2}' | awk -F/ '{print $1}' | grep -E '\b10.' | head -1)
  if [ -n "$IP" ]; then
    echo ""
    echo -e "\e[32m请用以下地址访问：\e[0m"
    echo -e "    \e[32m管理界面\e[0m： http://$IP:$PORT1/"
    echo -e "    \e[32m小雅AList\e[0m： http://$IP:$PORT2/"
  fi
  echo -e "\e[32m云服务器请用公网IP访问\e[0m"
fi
echo ""

echo -e "\e[33m默认端口变更为4567\e[0m"
