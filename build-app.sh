
rm -rf src/main/resources/static/assets && \
cd web-ui && \
echo "=== build web ui ===" && \
npm run build
cd .. && \
echo "=== build maven ===" && \
mvn clean package

java -jar target/alist-tvbox-1.0.jar --spring.profiles.active=mysql,standalone,production
