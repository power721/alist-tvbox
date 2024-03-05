BASE_DIR=/etc/xiaoya
PORT1=4567
PORT2=5344
PORT3=5345
TAG="native"
UPDATE=false
MEM_OPT="-Xmx512M"
MOUNT=""

usage(){
  echo "Usage: $0 [ -d BASE_DIR ] [ -p PORT1 ] [ -P PORT2 ] [ -t TAG ] [ -v MOUNT ] [ -m MEM_OPT ]"
  echo "-d BASE_DIR    数据目录，默认：/etc/xiaoya"
  echo "-p PORT1       管理界面端口，默认：4567"
  echo "-P PORT2       小雅AList端口，默认：5344"
  echo "-t TAG         Docker镜像标签，默认：native"
  echo "-u             检查镜像更新"
  echo "-v Host:Docker 路径挂载"
  echo "-m MEM_OPT     Java最大堆内存，默认：512M"
  exit 2
}

while getopts "d:p:P:e:m:t:v:hu" arg; do
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
        e)
            PORT3=${OPTARG}
            ;;
        m)
            MEM_OPT="-Xmx${OPTARG}M"
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
        h)
            usage
            ;;
        *)
            ;;
    esac
done

mkdir -p "$HOME/.config/atv"
echo "bridge ${*}" > "$HOME/.config/atv/cmd"

shift $((OPTIND-1))

if [ $# -gt 0 ]; then
	BASE_DIR=$1
fi

if [ $# -gt 1 ]; then
	PORT1=$2
fi

if [ $# -gt 2 ]; then
	PORT2=$3
fi

echo -e "\e[36m使用配置目录：\e[0m $BASE_DIR"
echo -e "\e[36m端口映射：\e[0m $PORT1:4567  $PORT2:80"

echo -e "\e[33m默认端口变更为4567\e[0m"

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
echo -e "\e[32m下载最新Docker镜像，平台：${platform}, image tag: ${TAG}\e[0m"
for i in 1 2 3 4 5
do
   docker pull --platform ${platform} haroldli/xiaoya-tvbox:${TAG} && break
done

NEW_IMAGE=$(docker images -q haroldli/xiaoya-tvbox:${TAG})
if [ "$UPDATE" = "true" ] && [ "$IMAGE_ID" = "$NEW_IMAGE" ]; then
  echo -e "\e[33m镜像没有更新\e[0m"
  exit
fi

echo -e "\e[33m重启应用\e[0m"
docker rm -f xiaoya-tvbox 2>/dev/null && \
docker run -d -p $PORT1:4567 -p $PORT2:80 -e ALIST_PORT=$PORT2 -e MEM_OPT="$MEM_OPT" -v "$BASE_DIR":/data ${MOUNT} --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:${TAG}

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
