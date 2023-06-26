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

if docker ps | grep -v xiaoya-tvbox | grep -v xiaoya-hostmode | grep -q xiaoya; then
  echo -e "\e[33m原版小雅Docker容器运行中。\e[0m"
  while true; do
      read -r -p "是否停止小雅Docker容器？[Y/N] " yn
      case $yn in
          [Yy]* ) docker rm -f xiaoya 2>/dev/null; break;;
          [Nn]* ) exit 1;;
          * ) echo "请输入'Y'或者'N'";;
      esac
  done
fi

echo -e "\e[36m使用配置目录：\e[0m $BASE_DIR"
echo -e "\e[36m端口映射：\e[0m $PORT1:8080  $PORT2:80"

mkdir -p $BASE_DIR

if ! grep "access.mypikpak.com" /etc/hosts >/dev/null
then
	echo -e "127.0.0.1\taccess.mypikpak.com" >> /etc/hosts
fi

docker image prune -f

echo -e "\e[32m下载最新Docker镜像\e[0m"
for i in 1 2 3 4 5
do
   docker pull haroldli/xiaoya-tvbox:latest && break
done

echo -e "\e[33m重启应用\e[0m"
docker rm -f xiaoya-tvbox 2>/dev/null && \
docker run -d -p $PORT1:8080 -p $PORT2:80 -v "$BASE_DIR":/data --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest

IP=$(ip a | grep -F '192.168.' | awk '{print $2}' | awk -F/ '{print $1}' | head -1)
if [ -n "$IP" ]; then
  echo -e "\e[32m请用以下地址访问：\e[0m"
  echo -e "    \e[32m管理界面\e[0m： http://$IP:$PORT1/"
  echo -e "    \e[32m小雅AList\e[0m： http://$IP:$PORT2/"
else
  echo -e "\e[32m云服务器请用公网IP访问\e[0m"
fi
echo ""
