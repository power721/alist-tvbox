
echo "=== build maven ==="
mvn clean package

cd target
java -Djarmode=layertools -jar alist-tvbox-1.0.jar extract
cd ..

echo "=== build haroldli/java:21 ==="
docker build -f docker/Dockerfile-jre --tag=haroldli/java:21 .

echo "=== build haroldli/alist-base ==="
docker build -f docker/Dockerfile-base --tag=haroldli/alist-base:latest .
