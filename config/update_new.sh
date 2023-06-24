PORT=5678

if [ $# -gt 0 ]; then
	PORT=$1
fi

docker image prune -f
docker pull haroldli/alist-tvbox && \
docker rm -f alist-tvbox && \
docker run -d -p $PORT:8080 --restart=always --name=alist-tvbox haroldli/alist-tvbox
