set -e

BASE_DIR=/etc/xiaoya
PORT1=4567
PORT2=5344
PORT3=5345
MEM_OPT="-Xmx512M"
PULL=true
BUILD_BASE=false
MOUNT=""

while getopts ":d:p:m:P:e:t:v:nb" arg; do
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
        v)
            MOUNT="${MOUNT} -v ${OPTARG}"
            ;;
        n)
            PULL=false
            ;;
        b)
            PULL=false
            BUILD_BASE=true
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

if [ $# -gt 2 ]; then
	PORT2=$3
fi

if [ $# -gt 3 ]; then
	MEM_OPT="-Xmx${4}M"
	echo "Java Memory: ${MEM_OPT}"
fi

rm -rf src/main/resources/static/assets && \
cd web-ui && \
echo "=== build web ui ===" && \
npm run build
cd .. && \
echo "=== build maven ===" && \
mvn clean package

cd target && java -Djarmode=layertools -jar alist-tvbox-1.0.jar extract && cd ..
pwd

[ "$BUILD_BASE" = "true" ] && echo "=== build haroldli/alist-base ===" && docker build -f docker/Dockerfile-base --tag=haroldli/alist-base:latest .

[ -d data ] || mkdir data
export TZ=Asia/Shanghai

echo -e "\e[36m使用配置目录：\e[0m $BASE_DIR"
echo -e "\e[36m端口映射：\e[0m $PORT1:4567  $PORT2:80"

[ "$PULL" = "true" ] && echo "=== pull haroldli/alist-base ===" && docker pull haroldli/alist-base

docker image prune -f
echo $((($(date +%Y) - 2023) * 366 + $(date +%j | sed 's/^0*//'))).$(date +%H%M) > data/version
echo "=== build haroldli/xiaoya-tvbox ==="
docker build -f docker/Dockerfile-xiaoya --tag=haroldli/xiaoya-tvbox:latest .
echo "=== restart xiaoya-tvbox ==="
sudo systemctl stop atv
docker rm -f xiaoya-tvbox alist-tvbox 2>/dev/null
docker run -d -p $PORT1:4567 -p $PORT2:80 -p 5566:5244 -e ALIST_PORT=$PORT2 -e INSTALL=xiaoya -e MEM_OPT="$MEM_OPT" -v "$BASE_DIR":/data ${MOUNT} --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest

sleep 1

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

docker logs -f xiaoya-tvbox
