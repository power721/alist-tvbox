mvn clean package
docker build --tag=alist-tvbox:latest .
docker images | grep alist-tvbox
