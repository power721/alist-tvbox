set -e

BUILD=true

while getopts "n" arg; do
    case "${arg}" in
        n)
            BUILD=false
            ;;
        *)
            ;;
    esac
done

if [ "$BUILD" = "true" ]; then
  rm -rf src/main/resources/static/assets && \
  cd web-ui && \
  echo "=== build web ui ===" && \
  npm run build
  cd .. && \
  echo "=== build maven ===" && \
  mvn clean package -DskipTests -Pnative
fi

cp target/atv /opt/atv

docker rm -f alist-tvbox
docker rm -f xiaoya-tvbox

echo "restart atv service"
sudo systemctl restart atv.service
sleep 3
sudo systemctl status atv.service
