docker image prune -f
docker pull haroldli/alist-tvbox && \
docker rm -f alist-tvbox && \
docker run -d -p 5678:8080 --restart=always --name=alist-tvbox haroldli/alist-tvbox

