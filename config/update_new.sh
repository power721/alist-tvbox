if docker ps | grep -v xiaoya-hostmode | grep -q xiaoya; then
  echo -e "\e[33m其它版本小雅Docker容器运行中。\e[0m"
  while true; do
      read -r -p "是否停止小雅Docker容器？[Y/N] " yn
      case $yn in
          [Yy]* ) docker rm -f xiaoya xiaoya-tvbox 2>/dev/null; break;;
          [Nn]* ) exit 1;;
          * ) echo "请输入'Y'或者'N'";;
      esac
  done
fi

PORT=5678

if [ $# -gt 0 ]; then
	PORT=$1
fi

echo -e "\e[36m端口映射：\e[0m $PORT:8080"

docker image prune -f
docker pull haroldli/alist-tvbox && \
docker rm -f alist-tvbox && \
docker run -d -p $PORT:8080 --restart=always --name=alist-tvbox haroldli/alist-tvbox

IP=$(ip a | grep -F '192.168.' | awk '{print $2}' | awk -F/ '{print $1}' | head -1)
if [ -n "$IP" ]; then
  echo -e "\e[32m请用以下地址访问：\e[0m"
  echo -e "    \e[32m管理界面\e[0m： http://$IP:$PORT/"
else
  echo -e "\e[32m云服务器请用公网IP访问\e[0m"
fi
echo ""
