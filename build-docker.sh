MOUNT=/etc/xiaoya
PORT=4567

rm -rf src/main/resources/static/assets && \
cd web-ui && \
npm run build || exit 1
cd ..

cp src/main/resources/application.yaml application-backup.yaml
sed -i '/- name: 本地/,+2d' src/main/resources/application.yaml
sed -i '/sites:/r add.txt' src/main/resources/application.yaml
mvn clean package || exit 1

mv application-backup.yaml src/main/resources/application.yaml

date +%j.%H%M > data/version
docker build --tag=haroldli/alist-tvbox:latest .

echo -e "\e[36m使用配置目录：\e[0m $MOUNT"
echo -e "\e[36m端口映射：\e[0m $PORT:45670"

docker run -d -p $PORT:4567 -v "$MOUNT":/data --name=alist-tvbox haroldli/alist-tvbox:latest

sleep 1

IP=$(ip a | grep -F '192.168.' | awk '{print $2}' | awk -F/ '{print $1}' | head -1)
if [ -n "$IP" ]; then
  echo -e "\e[32m请用以下地址访问：\e[0m"
  echo -e "    \e[32m管理界面\e[0m： http://$IP:$PORT/"
else
  echo -e "\e[32m云服务器请用公网IP访问\e[0m"
fi
echo ""

docker logs -f alist-tvbox
