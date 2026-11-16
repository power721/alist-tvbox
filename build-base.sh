
echo "=== build maven ==="
mvn clean package

cd target
java -Djarmode=tools -jar alist-tvbox-1.0.jar extract --layers --launcher --destination . --force
cd ..

echo "=== build haroldli/alist-base ==="
docker build -f docker/Dockerfile-base --tag=haroldli/alist-base:latest .
