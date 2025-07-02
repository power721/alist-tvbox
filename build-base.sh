
echo "=== build maven ==="
mvn clean package

cd target
java -Djarmode=layertools -jar alist-tvbox-1.0.jar extract
cd ..

echo "=== build haroldli/alist-base ==="
docker build -f docker/Dockerfile-base --tag=haroldli/alist-base:latest .
