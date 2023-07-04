BASE_DIR=/etc/xiaoya

if [ $# -gt 0 ]; then
	BASE_DIR=$1
fi

if docker ps | grep -v xiaoya-tvbox | grep -v xiaoya-hostmode | grep -v xiaoyakeeper | grep -v xiaoyaliu | grep -q xiaoya; then
  echo -e "\e[33m原版小雅Docker容器运行中。\e[0m"
  read -r -p "是否停止小雅Docker容器？[Y/N] " yn
  case $yn in
      [Yy]* ) docker rm -f xiaoya 2>/dev/null;;
      [Nn]* ) exit 0;;
  esac
fi

echo -e "\e[36m使用配置目录：\e[0m $BASE_DIR"

mkdir -p $BASE_DIR

if ! grep "access.mypikpak.com" /etc/hosts >/dev/null
then
	echo -e "127.0.0.1\taccess.mypikpak.com" >> /etc/hosts
fi

docker image prune -f

platform="linux/amd64"
tag="hostmode"
ARCH=$(uname -m)
if [ "$ARCH" = "armv7l" ]; then
  platform="linux/arm/v7"
  tag="arm-v7"
elif [ "$ARCH" = "aarch64" ]; then
    platform="linux/arm64"
fi

echo -e "\e[32m下载最新Docker镜像，平台：${platform}\e[0m"
for i in 1 2 3 4 5
do
   docker pull --platform ${platform} haroldli/xiaoya-tvbox:${tag} && break
done

echo -e "\e[33m重启应用\e[0m"
docker rm -f xiaoya-tvbox 2>/dev/null && \
docker run -d --network host -v "$BASE_DIR":/data --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:${tag}

IP=$(ip a | grep -F '192.168.' | awk '{print $2}' | awk -F/ '{print $1}' | head -1)
if [ -n "$IP" ]; then
  echo -e "\e[32m请用以下地址访问：\e[0m"
  echo -e "    \e[32m管理界面\e[0m： http://$IP:5678/"
  echo -e "    \e[32m小雅AList\e[0m： http://$IP:5234/"
else
  echo -e "\e[32m云服务器请用公网IP访问\e[0m"
fi
echo ""
