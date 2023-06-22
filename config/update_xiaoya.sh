BASE_DIR=/etc/xiaoya
PORT1=5678
PORT2=5244
if [ $# -gt 0 ]; then
	BASE_DIR=$1
fi

if [ $# -gt 1 ]; then
	PORT1=$2
fi

if [ $# -gt 2 ]; then
	PORT2=$3
fi

echo "config dir: $BASE_DIR"
echo "Port mappings: $PORT1:8080 $PORT2:80"

mkdir -p $BASE_DIR

if ! grep "access.mypikpak.com" /etc/hosts >/dev/null
then
	echo -e "127.0.0.1\taccess.mypikpak.com" >> /etc/hosts
fi

docker image prune -f

echo "下载最新Docker镜像"
for i in 1 2 3 4 5
do
   docker pull haroldli/xiaoya-tvbox:latest && break
done

echo "重启应用"
docker rm -f xiaoya-tvbox && \
docker run -d -p $PORT1:8080 -p $PORT2:80 -v "$BASE_DIR":/data --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest

echo "请尝试用以下IP访问："
ip a | grep inet | grep -v inet6 | awk '{print $2}' | awk -F/ '{print $1}'
