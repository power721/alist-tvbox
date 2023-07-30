set -e

MOUNT=/etc/xiaoya
PORT1=4567
PORT2=5344
MEM_OPT="-Xmx512M"

while getopts ":d:p:m:P:t:y" arg; do
    case "${arg}" in
        d)
            MOUNT=${OPTARG}
            ;;
        p)
            PORT1=${OPTARG}
            ;;
        P)
            PORT2=${OPTARG}
            ;;
        m)
            MEM_OPT="-Xmx${OPTARG}M"
            ;;
        *)
            ;;
    esac
done

shift $((OPTIND-1))

if [ $# -gt 0 ]; then
  MOUNT=$1
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

#rm -rf src/main/resources/static/assets && \
#cd web-ui && \
#npm run build || exit 1
#cd .. && \
#mvn clean package -DskipTests -Pnative || exit 1


echo -e "\e[36m使用配置目录：\e[0m $MOUNT"
echo -e "\e[36m端口映射：\e[0m $PORT1:4567  $PORT2:80"

docker image prune -f
date +%j.%H%M > data/version
docker build -f Dockerfile-native --tag=haroldli/xiaoya-tvbox:native . || exit 1
docker rm -f xiaoya-tvbox xiaoya alist-tvbox 2>/dev/null
docker run -d -p $PORT1:4567 -p $PORT2:80 -e ALIST_PORT=$PORT2 -e MEM_OPT="$MEM_OPT" -v "$MOUNT":/data --name=xiaoya-tvbox haroldli/xiaoya-tvbox:native

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
