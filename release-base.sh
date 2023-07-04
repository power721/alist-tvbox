mvn clean package || exit 1

cd target && java -Djarmode=layertools -jar alist-tvbox-1.0.jar extract && cd ..

docker build -f Dockerfile-base --build-arg TAG=latest --tag=haroldli/alist-base:latest .

docker push haroldli/alist-base:latest

docker build -f Dockerfile-base --build-arg TAG=hostmode --tag=haroldli/alist-base:hostmode .

docker push haroldli/alist-base:hostmode
