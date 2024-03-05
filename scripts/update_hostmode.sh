BASE_DIR=/etc/xiaoya
TAG="hostmode"
UPDATE=false
MOUNT=""

while getopts "d:t:v:u" arg; do
    case "${arg}" in
        d)
            BASE_DIR=${OPTARG}
            ;;
        t)
            TAG=${OPTARG}
            ;;
        u)
            UPDATE=true
            ;;
        v)
            MOUNT="${MOUNT} -v ${OPTARG}"
            ;;
        *)
            ;;
    esac
done

mkdir -p "$HOME/.config/atv"
echo "host ${*}" > "$HOME/.config/atv/cmd"

shift $((OPTIND-1))

if [ $# -gt 0 ]; then
	BASE_DIR=$1
fi

echo -e "\e[36m使用配置目录：\e[0m $BASE_DIR"

mkdir -p $BASE_DIR

if ! grep "access.mypikpak.com" /etc/hosts >/dev/null
then
	echo -e "127.0.0.1\taccess.mypikpak.com" >> /etc/hosts
fi

docker container prune -f --filter "label=MAINTAINER=Har01d"
docker image prune -f --filter "label=MAINTAINER=Har01d"
docker volume prune -f --filter "label=MAINTAINER=Har01d"

platform="linux/amd64"
ARCH=$(uname -m)
if [ "$ARCH" = "armv7l" ]; then
  echo "不支持的平台"
  exit 1
elif [ "$ARCH" = "aarch64" ]; then
    platform="linux/arm64"
fi

IMAGE_ID=$(docker images -q haroldli/xiaoya-tvbox:${TAG})
echo -e "\e[32m下载最新Docker镜像，平台：${platform}\e[0m"
for i in 1 2 3 4 5
do
   docker pull --platform ${platform} haroldli/xiaoya-tvbox:${TAG} && break
done

NEW_IMAGE=$(docker images -q haroldli/xiaoya-tvbox:${TAG})
if [ "$UPDATE" = "true" ] && [ "$IMAGE_ID" = "$NEW_IMAGE" ]; then
  echo -e "\e[33m镜像没有更新\e[0m"
  exit
fi

echo -e "\e[33m重启应用，host网络模式\e[0m"
docker rm -f xiaoya-tvbox 2>/dev/null && \
docker run -d --network host -v "$BASE_DIR":/data ${MOUNT} --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:${TAG}

echo -e "\n\e[32m请使用以下命令查看日志输出：\e[0m"
echo -e "    docker logs -f xiaoya-tvbox\n"

IP=$(ip a | grep -F '192.168.' | awk '{print $2}' | awk -F/ '{print $1}' | head -1)
if [ -n "$IP" ]; then
  echo ""
  echo -e "\e[32m请用以下地址访问：\e[0m"
  echo -e "    \e[32m管理界面\e[0m： http://$IP:4567/"
  echo -e "    \e[32m小雅AList\e[0m： http://$IP:5678/"
else
  echo -e "\e[32m云服务器请用公网IP访问\e[0m"
fi
echo ""

echo -e "\e[33m默认端口变更为4567\e[0m"
