set -e

if [ $# -gt 0 ]; then
  WD=$PWD
  cd ~/workspace/alist/ && \
  ~/workspace/alist/mybuild.sh
  cd $WD
fi

rm -rf src/main/resources/static/assets && \
cd web-ui && \
echo "=== build web ui ===" && \
npm run build
cd .. && \
echo "=== build maven ===" && \
mvn clean package -s ~/.m2/empty-settings.xml && \
sudo cp target/alist-tvbox-1.0.jar /opt/atv/alist-tvbox.jar && \
sudo systemctl restart atv.service && \
sudo systemctl status atv.service

#java -jar target/alist-tvbox-1.0.jar
