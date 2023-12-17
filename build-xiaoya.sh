set -e

BASE_DIR=/etc/xiaoya
PORT1=4567
PORT2=5344
PORT3=5345
MEM_OPT="-Xmx512M"
PULL=true
MOUNT=""

while getopts ":d:p:m:P:e:t:v:n" arg; do
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
npm run build || exit 1
cd .. && \
mvn clean package || exit 1

cd target && java -Djarmode=layertools -jar alist-tvbox-1.0.jar extract && cd ..
pwd

[ -d data ] || mkdir data
export TZ=Asia/Shanghai
num1=$(date +%Y)
num2=$(date +%j)
sum=$((($num1 - 2023) * 366 + $num2))
echo $sum.$(date +%H%M) > data/version

echo -e "\e[36m使用配置目录：\e[0m $BASE_DIR"
echo -e "\e[36m端口映射：\e[0m $PORT1:4567  $PORT2:80"

[ "$PULL" = "true" ] && docker pull haroldli/alist-base

docker image prune -f
date +%j.%H%M > data/version
docker build -f Dockerfile-xiaoya --tag=haroldli/xiaoya-tvbox:latest . || exit 1
docker rm -f xiaoya-tvbox alist-tvbox 2>/dev/null
docker run -d -p $PORT1:4567 -p $PORT2:80 -p 5566:5244 -e ALIST_PORT=$PORT2 -e MEM_OPT="$MEM_OPT" -v "$BASE_DIR":/data ${MOUNT} --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest

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
