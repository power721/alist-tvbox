rm -rf src/main/resources/static/assets && \
cd web-ui && \
npm run build && \
cd .. && \
git add src/main/resources/static

mvn clean package
docker build --tag=alist-tvbox:latest .
docker images | grep alist-tvbox
